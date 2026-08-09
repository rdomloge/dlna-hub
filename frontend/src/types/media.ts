export interface BrowsableItem {
  id: string;
  parentId: string;
  title: string;
  artist?: string;
  album?: string;
  duration?: string;
  resolution?: string;
  mimeType: string;
  size?: string;
  protocolInfo: string;
  isContainer: boolean;
  thumbnailUrl?: string;
  classType: string;
  description?: string;
  date?: string;
  resourceName?: string;
}

export interface BrowseResult {
  items: BrowsableItem[];
  total: number;
  index: number;
  count: number;
  updateId: string;
}
