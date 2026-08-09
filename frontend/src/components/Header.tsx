import { useNavigate } from 'react-router-dom';

interface HeaderProps {
  title?: string;
  showBack?: boolean;
}

export default function Header({
  title = 'DLNA Hub',
  showBack = false,
}: HeaderProps) {
  const navigate = useNavigate();

  return (
    <header className="h-14 bg-gray-900 text-white flex items-center px-4 fixed top-0 left-0 right-0 z-10">
      <button
        onClick={() => navigate('/')}
        className="mr-3 text-white hover:text-gray-300"
        aria-label="Home"
      >
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-6 w-6"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-4 0a1 1 0 01-1-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 01-1 1"
          />
        </svg>
      </button>
      {showBack && (
        <button
          onClick={() => navigate(-1)}
          className="mr-3 text-white hover:text-gray-300"
          aria-label="Go back"
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            className="h-6 w-6"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M15 19l-7-7 7-7"
            />
          </svg>
        </button>
      )}
      <h1 className="text-lg font-semibold">{title}</h1>
    </header>
  );
}
