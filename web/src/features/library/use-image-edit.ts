import { useCallback, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { croppedFileName, cropPixels, cropType, renderCrop, type CropRect } from "./image-crop";

/** What the viewer may scale an image to, and the step its buttons take. */
export const ZOOM = { min: 25, max: 400, step: 25 } as const;
const clampZoom = (value: number) => Math.min(ZOOM.max, Math.max(ZOOM.min, value));

/** The crop the owner applied: from here the viewer, the download, a save and a question all use it. */
export type EditedImage = {
  blob: Blob;
  filename: string;
  type: string;
  pixels: { width: number; height: number };
};

/**
 * How the previewed image is looked at and cut: its zoom and turn, the crop being drawn, and the crop applied.
 * The crop is encoded from the bytes the preview already holds and replaces what the viewer shows, so it is
 * usable in this session at once: cropped again, downloaded, saved as a new file or asked about.
 */
export function useImageEdit({
  filename,
  mediaType,
  onSaveImage,
}: {
  filename: string;
  mediaType: string | undefined;
  onSaveImage?: (file: File) => void | Promise<void>;
}) {
  const [zoom, setZoom] = useState(100);
  const [rotation, setRotation] = useState(0);
  const [cropping, setCropping] = useState(false);
  const [crop, setCrop] = useState<CropRect>();
  const [natural, setNatural] = useState<{ width: number; height: number }>();
  const [edited, setEdited] = useState<EditedImage>();

  const apply = useMutation({
    mutationFn: async ({ image, rect }: { image: Blob; rect: CropRect }) => {
      const type = cropType(edited?.type ?? mediaType ?? image.type);
      const bitmap = await createImageBitmap(image);
      const cropped = await renderCrop(bitmap, rect, type);
      const pixels = cropPixels(rect, { width: bitmap.width, height: bitmap.height });
      bitmap.close();
      // A second crop refines the same file rather than naming it twice.
      return {
        blob: cropped,
        filename: edited?.filename ?? croppedFileName(filename, type),
        type,
        pixels,
      };
    },
    onSuccess: (next) => {
      setEdited(next);
      setCropping(false);
      setNatural(undefined);
      showUpright();
    },
  });
  const save = useMutation({
    mutationFn: async (image: EditedImage) =>
      onSaveImage?.(new File([image.blob], image.filename, { type: image.type })),
  });

  /** A crop is drawn on the image as stored: upright, unzoomed and unrotated. */
  const showUpright = () => {
    setZoom(100);
    setRotation(0);
    setCrop(undefined);
    apply.reset();
  };
  // A wheel over the picture scales it, as an image viewer does; the listener must stay identical to detach.
  const zoomBy = useCallback(
    (deltaY: number) => setZoom((current) => clampZoom(current - Math.sign(deltaY) * 10)),
    [],
  );

  return {
    zoom,
    setZoom,
    rotation,
    turn: (degrees: number) => setRotation((current) => current + degrees),
    zoomBy,
    cropping,
    crop,
    setCrop,
    natural,
    setNatural,
    edited,
    cropFailed: apply.isError,
    busy: apply.isPending || save.isPending,
    applying: apply.isPending,
    saving: save.isPending,
    toggleCropping: () => {
      setCropping(!cropping);
      showUpright();
    },
    applyCrop: (image: Blob | undefined) => {
      if (crop && image) apply.mutate({ image, rect: crop });
    },
    saveEdited: () => {
      if (edited && onSaveImage) save.mutate(edited);
    },
    /** Back to the file as stored, as when another file of the gallery is shown. */
    reset: () => {
      setCropping(false);
      setNatural(undefined);
      setEdited(undefined);
      showUpright();
    },
    /** Drops the applied crop and shows the original again. */
    revert: () => {
      setEdited(undefined);
      setNatural(undefined);
      showUpright();
    },
  };
}

export type ImageEdit = ReturnType<typeof useImageEdit>;
