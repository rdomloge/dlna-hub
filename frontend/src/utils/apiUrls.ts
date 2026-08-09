export function buildBrowseUrl(
  serverId: string,
  objectId: string = '0',
  index: number = 0,
  count: number = 50
): string {
  return `/api/servers/${serverId}/browse?objectId=${encodeURIComponent(
    objectId
  )}&index=${index}&count=${count}`;
}

export function buildMetadataUrl(serverId: string, itemId: string): string {
  return `/api/servers/${serverId}/browse/${itemId}/metadata`;
}

export function buildThumbnailUrl(serverId: string, itemId: string): string {
  return `/api/servers/${serverId}/thumbnail/${itemId}`;
}

export function buildPlayerStatusUrl(playerId: string): string {
  return `/api/players/${playerId}/status`;
}

export function buildPlayerVolumeUrl(playerId: string): string {
  return `/api/players/${playerId}/volume`;
}
