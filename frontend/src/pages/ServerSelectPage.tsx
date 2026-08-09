import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import Header from '@/components/Header';
import LoadingSpinner from '@/components/LoadingSpinner';
import { getServers } from '@/api/servers';
import { useAppStore } from '@/store/useAppStore';
import type { MediaServer } from '@/types/server';

export default function ServerSelectPage() {
  const navigate = useNavigate();
  const setServers = useAppStore((s) => s.setServers);
  const setSelectedServer = useAppStore((s) => s.setSelectedServer);
  const [servers, setLocalServers] = useState<MediaServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchServers = useCallback(async () => {
    try {
      const data = await getServers();
      setLocalServers(data);
      setServers(data);
      setError(null);
    } catch (err: any) {
      setError(err.message || 'Failed to discover servers');
    } finally {
      setLoading(false);
    }
  }, [setServers]);

  useEffect(() => {
    fetchServers();
    const interval = setInterval(fetchServers, 10000);
    return () => clearInterval(interval);
  }, [fetchServers]);

  const handleSelect = (server: MediaServer) => {
    setSelectedServer(server);
    navigate('/players');
  };

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col">
      <Header title="Select a Server" showBack />
      <main className="flex-1 overflow-y-auto px-4 py-4 mt-14">
        {loading && servers.length === 0 ? (
          <LoadingSpinner />
        ) : error && servers.length === 0 ? (
          <div className="text-center py-8">
            <p className="text-red-600 mb-2">{error}</p>
            <button
              onClick={fetchServers}
              className="px-4 py-2 bg-gray-900 text-white rounded hover:bg-gray-800"
            >
              Retry
            </button>
          </div>
        ) : servers.length === 0 ? (
          <div className="text-center py-8 text-gray-600">
            <p className="mb-4">No DLNA servers found on the network.</p>
            <button
              onClick={fetchServers}
              className="px-4 py-2 bg-gray-900 text-white rounded hover:bg-gray-800"
            >
              Refresh
            </button>
          </div>
        ) : (
          <ul className="space-y-3">
            {servers.map((server) => (
              <li key={server.id}>
                <button
                  onClick={() => handleSelect(server)}
                  className="w-full text-left bg-white rounded-lg shadow-sm px-4 py-4 hover:bg-gray-50 transition-colors min-h-[60px]"
                >
                  <p className="font-semibold text-gray-900 text-lg">
                    {server.name}
                  </p>
                  <p className="text-sm text-gray-500 mt-1">
                    {server.manufacturer} {server.modelName}
                  </p>
                  <p className="text-xs text-gray-400 mt-0.5 truncate">
                    {server.location}
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
