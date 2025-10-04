/*
 * Lightweight TypeScript port of mpromonet/webrtc-streamer browser helper.
 * Handles signaling against the webrtc-streamer REST API so Vue components
 * can request low-latency WebRTC streams for Hikvision cameras.
 */

export type ConnectOptions = {
  videoUrl: string
  audioUrl?: string
  options?: string
  localStream?: MediaStream
  preferredCodec?: string
}

type IceServerResponse = RTCConfiguration | { iceServers: RTCIceServer[] }

export class WebRtcStreamerClient {
  private video: HTMLVideoElement
  private server: string
  private pc: (RTCPeerConnection & { peerid?: string }) | null = null
  private iceServers: IceServerResponse | null = null
  private earlyCandidates: RTCIceCandidateInit[] = []
  private readonly mediaConstraints: RTCOfferOptions = { offerToReceiveAudio: true, offerToReceiveVideo: true }
  private iceStateListener?: (state: RTCIceConnectionState) => void
  private connectedListener?: () => void
  private errorListener?: (reason: string) => void

  constructor(target: HTMLVideoElement | string, serverUrl?: string) {
    if (typeof target === 'string') {
      const node = document.getElementById(target)
      if (!node) throw new Error(`Video element ${target} 不存在`)
      if (!(node instanceof HTMLVideoElement)) throw new Error(`${target} 不是 <video>`)
      this.video = node
    } else {
      this.video = target
    }
    const origin = serverUrl || `${location.protocol}//${window.location.hostname}:${window.location.port}`
    this.server = origin.replace(/\/$/, '')
  }

  async connect(params: ConnectOptions): Promise<void> {
    await this.disconnect()

    if (!this.iceServers) {
      try {
        const resp = await fetch(`${this.server}/api/getIceServers`)
        if (!resp.ok) throw new Error(`getIceServers HTTP ${resp.status}`)
        this.iceServers = (await resp.json()) as IceServerResponse
      } catch (err) {
        console.error('[WebRtcStreamer] getIceServers failed', err)
        throw err
      }
    }

    this.createPeerConnection()
    const peer = this.pc
    if (!peer?.peerid) throw new Error('PeerConnection 未创建 peerid')

    const query = new URLSearchParams({ peerid: String(peer.peerid), url: params.videoUrl })
    if (params.audioUrl) query.set('audiourl', params.audioUrl)
    if (params.options) query.set('options', params.options)

    const callUrl = `${this.server}/api/call?${query.toString()}`

    if (params.localStream) {
      params.localStream.getTracks().forEach(track => peer.addTrack(track, params.localStream!))
    }

    this.earlyCandidates = []

    const offer = await peer.createOffer(this.mediaConstraints)

    if (params.preferredCodec) {
      offer.sdp = this.filterPreferredCodec(offer.sdp || '', params.preferredCodec)
    }

    await peer.setLocalDescription(offer)

    const callResp = await fetch(callUrl, {
      method: 'POST',
      body: JSON.stringify(offer),
      headers: { 'Content-Type': 'application/json' }
    })
    if (!callResp.ok) {
      if (callResp.status === 404) {
        throw new Error('WebRTC stream not found (404)')
      }
      throw new Error(`call HTTP ${callResp.status}`)
    }

    const answer = await callResp.json()
    await peer.setRemoteDescription(new RTCSessionDescription(answer))

    // apply candidates collected before remote description
    while (this.earlyCandidates.length) {
      const candidate = this.earlyCandidates.shift()!
      await this.addIceCandidate(peer.peerid!, candidate)
    }

    await this.pollIceCandidates()
  }

  async disconnect(): Promise<void> {
    if (this.video.srcObject instanceof MediaStream) {
      const stream = this.video.srcObject
      stream.getTracks().forEach(track => track.stop())
      this.video.srcObject = null
    }

    if (this.pc) {
      if (this.pc.peerid !== undefined) {
        try {
          const resp = await fetch(`${this.server}/api/hangup?peerid=${this.pc.peerid}`)
          if (!resp.ok) console.warn('[WebRtcStreamer] hangup failed', resp.status)
        } catch (err) {
          console.warn('[WebRtcStreamer] hangup error', err)
        }
      }

      try {
        this.pc.close()
      } catch (err) {
        console.warn('[WebRtcStreamer] close error', err)
      }
      this.pc = null
    }
  }

  private createPeerConnection(): void {
    const config: RTCConfiguration = this.iceServers && 'iceServers' in this.iceServers
      ? this.iceServers
      : { iceServers: [] }

    const pc = new RTCPeerConnection(config)
    pc.peerid = Math.random().toString(36).slice(2)

    pc.onicecandidate = event => {
      if (!event.candidate) return
      if (pc.currentRemoteDescription) {
        void this.addIceCandidate(pc.peerid!, event.candidate.toJSON())
      } else {
        this.earlyCandidates.push(event.candidate.toJSON())
      }
    }

    pc.oniceconnectionstatechange = () => {
      const state = pc.iceConnectionState
      if (this.iceStateListener) {
        try {
          this.iceStateListener(state)
        } catch (err) {
          console.warn('[WebRtcStreamer] iceState listener error', err)
        }
      }
      if (state === 'connected') {
        this.video.style.opacity = '1'
      } else if (state === 'disconnected') {
        this.video.style.opacity = '0.4'
      } else if (state === 'failed' || state === 'closed') {
        this.video.style.opacity = '0.6'
        if (this.errorListener) {
          try {
            this.errorListener(`Peer connection ${state}`)
          } catch (err) {
            console.warn('[WebRtcStreamer] error listener error', err)
          }
        }
      } else if (state === 'new') {
        void this.pollIceCandidates()
      }
    }

    pc.ontrack = event => {
      const [stream] = event.streams
      if (stream) {
        this.video.srcObject = stream
        const playPromise = this.video.play()
        if (playPromise) {
          playPromise.catch(err => console.warn('[WebRtcStreamer] autoplay failed', err))
        }
        if (this.connectedListener) {
          try {
            this.connectedListener()
          } catch (err) {
            console.warn('[WebRtcStreamer] connected listener error', err)
          }
        }
      }
    }

    try {
      pc.createDataChannel('client')
    } catch (err) {
      console.warn('[WebRtcStreamer] data channel create failed', err)
    }

    this.pc = pc
  }

  private filterPreferredCodec(sdp: string, pref: string): string {
    if (!sdp) return sdp
    const lines = sdp.split('\n')
    let [prefkind, prefcodec] = pref.toLowerCase().split('/')
    if (prefkind !== 'audio' && prefkind !== 'video') {
      prefcodec = prefkind
      prefkind = 'video'
    }

    let currentMediaType: string | null = null
    const sections: string[][] = []
    let current: string[] = []

    lines.forEach(line => {
      if (line.startsWith('m=')) {
        if (current.length) sections.push(current)
        current = [line]
      } else {
        current.push(line)
      }
    })
    if (current.length) sections.push(current)

    const processed = sections.map(section => {
      const first = section[0]
      currentMediaType = first.substring(2, first.indexOf(' '))
      if (currentMediaType !== prefkind) {
        return section.join('\n')
      }

      const rtpLines = section.filter(l => l.startsWith('a=rtpmap:'))
      const preferred = rtpLines
        .filter(l => l.toLowerCase().includes(prefcodec))
        .map(l => l.split(':')[1].split(' ')[0])

      if (!preferred.length) {
        return section.join('\n')
      }

      const mLineParts = first.split(' ')
      const newMLine = [...mLineParts.slice(0, 3), ...preferred].join(' ')
      const filtered = section.filter(line => {
        if (line.startsWith('a=rtpmap:') || line.startsWith('a=fmtp:') || line.startsWith('a=rtcp-fb:')) {
          return preferred.some(payload => line.startsWith(`a=${line.split(':')[0].split('a=')[1]}:${payload}`))
        }
        return true
      })

      return [newMLine, ...filtered.slice(1)].join('\n')
    })

    return processed.join('\n')
  }

  private async addIceCandidate(peerId: string, candidate: RTCIceCandidateInit): Promise<void> {
    try {
      const resp = await fetch(`${this.server}/api/addIceCandidate?peerid=${peerId}`, {
        method: 'POST',
        body: JSON.stringify(candidate),
        headers: { 'Content-Type': 'application/json' }
      })
      if (!resp.ok) {
        console.warn('[WebRtcStreamer] addIceCandidate failed', resp.status)
      }
    } catch (err) {
      console.warn('[WebRtcStreamer] addIceCandidate error', err)
    }
  }

  private async pollIceCandidates(): Promise<void> {
    if (!this.pc?.peerid) return
    try {
      const resp = await fetch(`${this.server}/api/getIceCandidate?peerid=${this.pc.peerid}`)
      if (!resp.ok) return
      const candidates: RTCIceCandidateInit[] = await resp.json()
      if (!candidates?.length) return
      for (const c of candidates) {
        try {
          await this.pc?.addIceCandidate(new RTCIceCandidate(c))
        } catch (err) {
          console.warn('[WebRtcStreamer] addIceCandidate(local) failed', err)
        }
      }
    } catch (err) {
      console.warn('[WebRtcStreamer] pollIceCandidates error', err)
    }
  }

  public onIceState(listener: (state: RTCIceConnectionState) => void): void {
    this.iceStateListener = listener
  }

  public onConnected(listener: () => void): void {
    this.connectedListener = listener
  }

  public onError(listener: (reason: string) => void): void {
    this.errorListener = listener
  }
}
