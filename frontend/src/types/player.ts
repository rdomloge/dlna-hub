export interface Renderer {
  id: string;
  name: string;
  ip: string;
  port: number;
  manufacturer: string;
  modelName: string;
  deviceType: string;
  presentationUrl: string;
  supportedProtocols: string[];
  transportCapabilities: string[];
}
