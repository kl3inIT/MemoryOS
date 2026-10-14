import type { CSSProperties, HTMLAttributes, ReactNode } from "react";
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

export type SortableHandle = {
  /** Spread on the drag handle: pointer drag and Space/arrow keyboard reordering. */
  attributes: HTMLAttributes<HTMLElement>;
  style: CSSProperties;
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
  onReorder: (items: T[]) => void;
  children: (item: T, handle: SortableHandle) => ReactNode;
}) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const ids = items.map(getId);
  function end(event: DragEndEvent) {
    const from = ids.indexOf(String(event.active.id));
    const to = event.over ? ids.indexOf(String(event.over.id)) : -1;
    if (from >= 0 && to >= 0 && from !== to) onReorder(arrayMove(items, from, to));
  }
  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      modifiers={[keepInList]}
      onDragEnd={end}
    >
      <SortableContext items={ids} strategy={verticalListSortingStrategy}>
        {items.map((item) => (
          <SortableItem key={getId(item)} id={getId(item)}>
            {(handle) => children(item, handle)}
          </SortableItem>
        ))}
      </SortableContext>
    </DndContext>
  );
}

function SortableItem({
  id,
  children,
}: {
  id: string;
  children: (handle: SortableHandle) => ReactNode;
}) {
  const sortable = useSortable({ id });
  return children({
    attributes: { ...sortable.attributes, ...sortable.listeners } as HTMLAttributes<HTMLElement>,
    style: {
      transform: CSS.Translate.toString(sortable.transform),
      transition: sortable.transition,
    },
    setNodeRef: sortable.setNodeRef,
    setHandleRef: sortable.setActivatorNodeRef,
    dragging: sortable.isDragging,
  });
}
