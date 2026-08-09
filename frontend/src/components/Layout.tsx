import { type ReactNode } from 'react';

interface LayoutProps {
  children: ReactNode;
}

export default function Layout({ children }: LayoutProps) {
  return (
    <div className="min-h-screen bg-gray-100 flex flex-col">
      <main className="flex-1 overflow-y-auto px-4 py-4">{children}</main>
    </div>
  );
}
