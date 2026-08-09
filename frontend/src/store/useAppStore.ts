import { create } from 'zustand';
import type { MediaServer } from '@/types/server';
import type { Renderer } from '@/types/player';

interface AppState {
  selectedServer: MediaServer | null;
  setSelectedServer: (server: MediaServer | null) => void;
  selectedPlayer: Renderer | null;
  setSelectedPlayer: (player: Renderer | null) => void;
  servers: MediaServer[];
  setServers: (servers: MediaServer[]) => void;
  players: Renderer[];
  setPlayers: (players: Renderer[]) => void;
}

export const useAppStore = create<AppState>((set) => ({
  selectedServer: null,
  setSelectedServer: (server) => set({ selectedServer: server }),
  selectedPlayer: null,
  setSelectedPlayer: (player) => set({ selectedPlayer: player }),
  servers: [],
  setServers: (servers) => set({ servers }),
  players: [],
  setPlayers: (players) => set({ players }),
}));
