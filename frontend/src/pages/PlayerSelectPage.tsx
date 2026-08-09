import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import Header from '@/components/Header';
import LoadingSpinner from '@/components/LoadingSpinner';
import { getPlayers } from '@/api/players';
import { useAppStore } from '@/store/useAppStore';
import type { Renderer } from '@/types/player';

export default function PlayerSelectPage() {
  const navigate = useNavigate();
  const selectedServer = useAppStore((s) => s.selectedServer);
  const setPlayers = useAppStore((s) => s.setPlayers);
  const setSelectedPlayer = useAppStore((s) => s.setSelectedPlayer);
  const [players, setLocalPlayers] = useState<Renderer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!selectedServer) {
      navigate('/servers');
      return;
    }
  }, [selectedServer, navigate]);

  const fetchPlayers = useCallback(async () => {
    try {
      const data = await getPlayers();
      setLocalPlayers(data);
      setPlayers(data);
      setError(null);
    } catch (err: any) {
      setError(err.message || 'Failed to discover players');
    } finally {
      setLoading(false);
    }
  }, [setPlayers]);

  useEffect(() => {
    if (!selectedServer) return;
    fetchPlayers();
    const interval = setInterval(fetchPlayers, 10000);
    return () => clearInterval(interval);
  }, [fetchPlayers, selectedServer]);

  const handleSelect = (player: Renderer) => {
    setSelectedPlayer(player);
    navigate('/browse');
  };

  if (!selectedServer) {
    return (
      <div className="min-h-screen bg-gray-100 flex flex-col">
        <Header title="Select a Player" showBack />
        <main className="flex-1 flex items-center justify-center mt-14">
          <LoadingSpinner />
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col">
      <Header title="Select a Player" showBack />
      <main className="flex-1 overflow-y-auto px-4 py-4 mt-14">
        {loading && players.length === 0 ? (
          <LoadingSpinner />
        ) : error && players.length === 0 ? (
          <div className="text-center py-8">
            <p className="text-red-600 mb-2">{error}</p>
            <button
              onClick={fetchPlayers}
              className="px-4 py-2 bg-gray-900 text-white rounded hover:bg-gray-800"
            >
              Retry
            </button>
          </div>
        ) : players.length === 0 ? (
          <div className="text-center py-8 text-gray-600">
            <p className="mb-4">No DLNA players found on the network.</p>
            <button
              onClick={fetchPlayers}
              className="px-4 py-2 bg-gray-900 text-white rounded hover:bg-gray-800"
            >
              Refresh
            </button>
          </div>
        ) : (
          <ul className="space-y-3">
            {players.map((player) => (
              <li key={player.id}>
                <button
                  onClick={() => handleSelect(player)}
                  className="w-full text-left bg-white rounded-lg shadow-sm px-4 py-4 hover:bg-gray-50 transition-colors min-h-[60px]"
                >
                  <p className="font-semibold text-gray-900 text-lg">
                    {player.name}
                  </p>
                  <p className="text-sm text-gray-500 mt-1">
                    {player.manufacturer} {player.modelName}
                  </p>
                  <p className="text-xs text-gray-400 mt-0.5">
                    {player.ip}:{player.port}
                  </p>
                </button>
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  );
}
