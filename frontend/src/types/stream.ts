export type HlsStreamSource = {
  kind: 'hls'
  url: string
}

export type WebRtcStreamSource = {
  kind: 'webrtc'
  /** Base URL of the webrtc-streamer service, for example http://127.0.0.1:8000 */
  server: string
  /** Camera RTSP URL (or registered alias) passed to webrtc-streamer */
  url: string
  /** Optional audio URL (rarely used for Hikvision, keep empty string when unused) */
  audioUrl?: string
  /** Optional query string appended as webrtc-streamer "options" */
  options?: string
  /** Preferred codec string such as "video/H264" */
  preferCodec?: string
}

export type StreamSource = HlsStreamSource | WebRtcStreamSource

export function isWebRtcSource(source: StreamSource | undefined | null): source is WebRtcStreamSource {
  return !!source && source.kind === 'webrtc'
}

export function isHlsSource(source: StreamSource | undefined | null): source is HlsStreamSource {
  return !!source && source.kind === 'hls'
}
