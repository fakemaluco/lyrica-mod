declare const Spicetify: {
  Player: {
    data?: PlayerState;
    isPlaying(): boolean;
    getProgress(): number;
    getDuration?(): number;
    getVolume?(): number;
    getShuffle?(): boolean;
    getRepeat?(): number;
    getHeart?(): boolean;
    togglePlay(): void;
    back(): void;
    next(): void;
    seek(position: number): void;
    setVolume?(volume: number): void;
    toggleShuffle?(): void;
    toggleRepeat?(): void;
    toggleHeart?(): void;
    setHeart?(status: boolean): void;
    addEventListener(event: string, callback: (event?: { data?: PlayerState | number }) => void): void;
  };
  PopupModal: {
    display(options: { title: string; content: HTMLElement; isLarge?: boolean }): void;
    hide(): void;
  };
  Topbar?: {
    Button: new (
      label: string,
      icon: string,
      onClick: (self: {
        label: string;
        icon: string;
        disabled: boolean;
        element: HTMLButtonElement;
      }) => void,
      disabled?: boolean,
      isRight?: boolean,
    ) => {
      label: string;
      icon: string;
      disabled: boolean;
      element: HTMLButtonElement;
      tippy?: { setContent(value: string): void };
    };
  };
  showNotification(message: string, isError?: boolean): void;
};

interface PlayerState {
      item?: {
        uri?: string;
        name?: string;
        duration?: { milliseconds?: number } | number;
        artists?: Array<{ name?: string; uri?: string }>;
        images?: Array<{ url?: string; label?: string }>;
        show?: { images?: Array<{ url?: string; label?: string }> };
        metadata?: Record<string, string>;
      };
}
