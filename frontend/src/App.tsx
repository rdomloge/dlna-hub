import { BrowserRouter, Routes, Route } from 'react-router-dom';
import ErrorBoundary from '@/components/ErrorBoundary';
import HomePage from '@/pages/HomePage';
import ServerSelectPage from '@/pages/ServerSelectPage';
import PlayerSelectPage from '@/pages/PlayerSelectPage';
import BrowsePage from '@/pages/BrowsePage';
import PlaybackPage from '@/pages/PlaybackPage';

function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/servers" element={<ServerSelectPage />} />
          <Route path="/players" element={<PlayerSelectPage />} />
          <Route path="/browse" element={<BrowsePage />} />
          <Route path="/playback" element={<PlaybackPage />} />
        </Routes>
      </BrowserRouter>
    </ErrorBoundary>
  );
}

export default App;
