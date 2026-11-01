import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type HTMLAttributes,
  type ReactNode,
  type SyntheticEvent,
} from "react";
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type Modifier,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

/** A row moves only up and down, and only inside its list: it cannot be pulled sideways or drag the page along. */
const keepInList: Modifier = ({ transform, draggingNodeRect, containerNodeRect }) => {
  if (!draggingNodeRect || !containerNodeRect) return { ...transform, x: 0 };
  const highest = containerNodeRect.top - draggingNodeRect.top;
  const lowest = containerNodeRect.bottom - draggingNodeRect.bottom;
  return { ...transform, x: 0, y: Math.min(Math.max(transform.y, highest), lowest) };
};

/**
 * Letting go of a dragged row also clicks what is under the pointer, and dnd-kit stops that click from reaching the
 * page but not the browser, which then follows a link. This runs ahead of dnd-kit's own capture listener and cancels it.
 */
const cancelClick = (event: MouseEvent) => event.preventDefault();

/** What a row needs to be dragged. Its identity holds while the list is dragged, so a memoized row stays put. */
export type SortableDrag = {
  /** Spread on the drag handle: pointer drag and Space/arrow keyboard reordering. */
  attributes: HTMLAttributes<HTMLElement>;
  /** Spread on the whole row to drag it by the pointer from anywhere. */
  rowProps: Pick<HTMLAttributes<HTMLElement>, "onPointerDown">;
  /** The ref callback of the handle. */
  handleNode: (node: HTMLElement | null) => void;
};

export type SortableHandle = {
  drag: SortableDrag;
  /** Spread on the drag handle: pointer drag and Space/arrow keyboard reordering. */
  attributes: HTMLAttributes<HTMLElement>;
  style: CSSProperties;
  /** Spread on the whole row to drag it by the pointer from anywhere; keyboard reordering stays on the handle. */
  rowProps: Pick<HTMLAttributes<HTMLElement>, "onPointerDown">;
  setNodeRef: (node: HTMLElement | null) => void;
  setHandleRef: (node: HTMLElement | null) => void;
  dragging: boolean;
};

/** A vertical list reordered by a handle with pointer or keyboard (dnd-kit, as Onyx uses for pinned agents). */
export function SortableList<T>({
  items,
  getId,
  onReorder,
  children,
}: {
  items: T[];
  getId: (item: T) => string;
  /** A save that is still running keeps the dropped order on screen until it ends, whichever way it ends. */
  onReorder: (items: T[]) => void | Promise<unknown>;
  children: (item: T, handle: SortableHandle) => ReactNode;
}) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  // The new order shows in the same render that ends the drag. Waiting for the owner's data would put the row back
  // in its old place for a frame before it jumped to the new one. It gives way as soon as the owner's order changes.
  const [dropped, setDropped] = useState<T[]>();
  const ownerOrder = items.map(getId).join("|");
  const [seenOrder, setSeenOrder] = useState(ownerOrder);
  if (seenOrder !== ownerOrder) {
    setSeenOrder(ownerOrder);
    setDropped(undefined);
  }
  const shown = dropped ?? items;
  const ids = shown.map(getId);
  // A list that unmounts mid-drag or before the drop's click arrives must not leave the page swallowing clicks.
  const settling = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(
    () => () => {
      clearTimeout(settling.current);
      window.removeEventListener("click", cancelClick, { capture: true });
    },
    [],
  );
  function start() {
    clearTimeout(settling.current);
    window.addEventListener("click", cancelClick, { capture: true });
  }
  function settle() {
    clearTimeout(settling.current);
    settling.current = setTimeout(
      () => window.removeEventListener("click", cancelClick, { capture: true }),
      100,
    );
  }
  function end(event: DragEndEvent) {
    settle();
    const from = ids.indexOf(String(event.active.id));
    const to = event.over ? ids.indexOf(String(event.over.id)) : -1;
    if (from < 0 || to < 0 || from === to) return;
    const next = arrayMove(shown, from, to);
    setDropped(next);
    void Promise.resolve(onReorder(next))
      .catch(() => undefined)
      .finally(() => setDropped(undefined));
  }
  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      modifiers={[keepInList]}
      onDragStart={start}
      onDragEnd={end}
      onDragCancel={settle}
    >
      <SortableContext items={ids} strategy={verticalListSortingStrategy}>
        {shown.map((item) => (
          <SortableItem key={getId(item)} id={getId(item)}>
            {(handle) => children(item, handle)}
          </SortableItem>
        ))}
      </SortableContext>
    </DndContext>
  );
}

/**
 * A dialog or menu a row opens is portalled in the DOM but stays in the row's React tree, so its pointer and key events
 * bubble to the row. The drag listeners take only events from the row's own DOM, or selecting text in a dialog drags it.
 */
function ownDomOnly(
  listeners: ReturnType<typeof useSortable>["listeners"],
): Pick<HTMLAttributes<HTMLElement>, "onPointerDown" | "onKeyDown"> {
  const own: Record<string, (event: SyntheticEvent<HTMLElement>) => void> = {};
  for (const [name, listener] of Object.entries(listeners ?? {})) {
    own[name] = (event) => {
      if (event.currentTarget.contains(event.target as Node)) listener(event);
    };
  }
  return own;
}

function SortableItem({
  id,
  children,
}: {
  id: string;
  children: (handle: SortableHandle) => ReactNode;
}) {
  const sortable = useSortable({ id });
  const listeners = useMemo(() => ownDomOnly(sortable.listeners), [sortable.listeners]);
  const drag = useMemo<SortableDrag>(
    () => ({
      attributes: { ...sortable.attributes, ...listeners } as HTMLAttributes<HTMLElement>,
      rowProps: { onPointerDown: listeners.onPointerDown },
      handleNode: sortable.setActivatorNodeRef,
    }),
    [sortable.attributes, listeners, sortable.setActivatorNodeRef],
  );
  return children({
    drag,
    attributes: { ...sortable.attributes, ...listeners } as HTMLAttributes<HTMLElement>,
    style: {
      transform: CSS.Translate.toString(sortable.transform),
      transition: sortable.transition,
    },
    rowProps: { onPointerDown: listeners.onPointerDown },
    setNodeRef: sortable.setNodeRef,
    setHandleRef: sortable.setActivatorNodeRef,
    dragging: sortable.isDragging,
  });
}
