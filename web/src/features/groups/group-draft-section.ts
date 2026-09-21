export type GroupDraftSectionHandle = {
  save: () => Promise<boolean>;
  reset: () => void;
};

export type GroupDraftStateChange = (dirty: boolean, pending: boolean) => void;
