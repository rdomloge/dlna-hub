import { useNavigate } from 'react-router-dom';
import Header from '@/components/Header';

export default function HomePage() {
  const navigate = useNavigate();

  return (
    <div className="min-h-screen bg-gray-100 flex flex-col">
      <Header />
      <main className="flex-1 flex items-center justify-center px-4 mt-14">
        <div className="text-center max-w-md">
          <h1 className="text-3xl font-bold text-gray-800 mb-2">
            Welcome to DLNA Hub
          </h1>
          <p className="text-gray-600 mb-8">
            Browse DLNA servers, select players, and control playback from your
            device.
          </p>
          <button
            onClick={() => navigate('/servers')}
            className="px-6 py-3 bg-gray-900 text-white rounded-lg font-medium hover:bg-gray-800 transition-colors"
          >
            Get Started
          </button>
        </div>
      </main>
    </div>
  );
}
