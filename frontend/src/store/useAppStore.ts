import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { MediaServer } from '@/types/server';
import type { Renderer } from '@/types/player';

export interface BrowseState {
  objectId: string;
  breadcrumb: { id: string; title: string }[];
  sortBy: string;
}

/**
 * Which implementation backs date-ordering sorts.
 * - `legacy`: the current blocking browse (capped, eventually-consistent effective dates).
 * - `stream`: the new SSE effective-date stream (backend computes a complete uncapped cache in
 *   the background and pushes each container's date as it is known).
 */
export type DateSortMode = 'legacy' | 'stream';

interface AppState {
  selectedServer: MediaServer | null;
  setSelectedServer: (server: MediaServer | null) => void;
  selectedPlayer: Renderer | null;
  setSelectedPlayer: (player: Renderer | null) => void;
  servers: MediaServer[];
  setServers: (servers: MediaServer[]) => void;
  players: Renderer[];
  setPlayers: (players: Renderer[]) => void;
  browseState: BrowseState;
  setBrowseState: (state: BrowseState) => void;
  updateBrowseState: (patch: Partial<BrowseState>) => void;
  dateSortMode: DateSortMode;
  setDateSortMode: (mode: DateSortMode) => void;
}

const defaultBrowseState: BrowseState = {
  objectId: '0',
  breadcrumb: [{ id: '0', title: 'Root' }],
  sortBy: '',
};

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      selectedServer: null,
      setSelectedServer: (server) =>
        set((state) => ({
          selectedServer: server,
          browseState: state.selectedServer?.id === server?.id
            ? state.browseState
            : defaultBrowseState,
        })),
      selectedPlayer: null,
      setSelectedPlayer: (player) => set({ selectedPlayer: player }),
      servers: [],
      setServers: (servers) => set({ servers }),
      players: [],
      setPlayers: (players) => set({ players }),
      browseState: defaultBrowseState,
      setBrowseState: (state) => set({ browseState: state }),
      updateBrowseState: (patch) =>
        set((s) => ({ browseState: { ...s.browseState, ...patch } })),
      dateSortMode: 'stream',
      setDateSortMode: (mode) => set({ dateSortMode: mode }),
    }),
    {
      name: 'dlna-app-state',
      partialize: (state) => ({
        selectedServer: state.selectedServer,
        selectedPlayer: state.selectedPlayer,
        browseState: state.browseState,
        dateSortMode: state.dateSortMode,
      }),
    }
  )
);
