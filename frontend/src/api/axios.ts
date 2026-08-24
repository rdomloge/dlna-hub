import axios from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  // A client-sorted browse of a large folder fetches the whole container before it can
  // return a page, and a date sort may also crawl sub-folders. nginx allows 60s; the client
  // must not give up first, or it retries a request the backend is still working on.
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use((config) => {
  console.debug('[API] Request:', config.method?.toUpperCase(), config.url);
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    console.error('[API] Error:', error.response?.status, error.message);
    return Promise.reject(error);
  }
);

export default api;
