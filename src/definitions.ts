export interface NPEPlugin {
  /**
   * Extract YouTube video stream information
   * @param options - The video URL options
   * @returns Promise with stream information including video and audio streams
   */
  extractStreamInfo(options: { videoUrl: string }): Promise<StreamInfoResult>;
}

export interface StreamInfoResult {
  success: boolean;
  error?: string;
  streamInfo?: {
    title: string;
    duration: number;
    uploader: string;
    viewCount: number;
    thumbnailUrl: string;
    videoStreams: VideoStream[];
    audioStreams: AudioStream[];
    videoOnlyStreams: VideoStream[];
  };
}

export interface VideoStream {
  url: string;
  format: string;
  resolution: string;
  fps?: number;
}

export interface AudioStream {
  url: string;
  format: string;
  bitrate: number;
}
