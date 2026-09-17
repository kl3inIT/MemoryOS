import type { CSSProperties, HTMLAttributes, ReactNode } from "react";
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

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
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={end}>
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
