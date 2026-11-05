import { useCallback, useEffect, useRef, useState } from 'react'
import type { Client, IMessage } from '@stomp/stompjs'
import { Camera, CameraOff, Mic, MicOff, Phone, PhoneOff } from 'lucide-react'

type Role = 'RECRUITER' | 'CANDIDATE'
type Signal = { type: 'OFFER' | 'ANSWER' | 'ICE_CANDIDATE' | 'END_CALL'; attemptId: string; senderId: string; senderRole: Role; senderName: string; payload?: Record<string, unknown> }

export default function LiveVideoCall({ client, connected, attemptId, role }: { client: Client | null; connected: boolean; attemptId: string; role: Role }) {
  const [incoming, setIncoming] = useState<Signal | null>(null)
  const [active, setActive] = useState(false)
  const [muted, setMuted] = useState(false)
  const [cameraOff, setCameraOff] = useState(false)
  const [audioLevel, setAudioLevel] = useState(0)
  const [callError, setCallError] = useState('')
  const peer = useRef<RTCPeerConnection | null>(null)
  const localStream = useRef<MediaStream | null>(null)
  const remoteStream = useRef<MediaStream | null>(null)
  const localVideo = useRef<HTMLVideoElement | null>(null)
  const remoteVideo = useRef<HTMLVideoElement | null>(null)
  const pendingIce = useRef<RTCIceCandidateInit[]>([])
  const offer = useRef<Signal | null>(null)
  const meterContext = useRef<AudioContext | null>(null)
  const meterFrame = useRef(0)

  const send = useCallback((type: Signal['type'], payload: Record<string, unknown> = {}) => {
    if (!client?.connected) throw new Error('Live connection is not ready. Reconnect and try again.')
    client.publish({ destination: `/app/attempt/${attemptId}/webrtc-signal`, body: JSON.stringify({ type, payload }) })
  }, [attemptId, client])

  const stopCall = useCallback((notify: boolean) => {
    if (notify && client?.connected) {
      try { send('END_CALL') } catch { /* socket may have closed during teardown */ }
    }
    peer.current?.getSenders().forEach(sender => sender.track?.stop())
    peer.current?.close()
    peer.current = null
    localStream.current?.getTracks().forEach(track => track.stop())
    localStream.current = null
    cancelAnimationFrame(meterFrame.current)
    void meterContext.current?.close()
    meterContext.current = null
    setAudioLevel(0)
    remoteStream.current?.getTracks().forEach(track => track.stop())
    remoteStream.current = null
    if (localVideo.current) localVideo.current.srcObject = null
    if (remoteVideo.current) remoteVideo.current.srcObject = null
    pendingIce.current = []
    offer.current = null
    setIncoming(null); setActive(false); setMuted(false); setCameraOff(false)
  }, [client, send])

  const createPeer = useCallback(async () => {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true })
    localStream.current = stream
    if (localVideo.current) localVideo.current.srcObject = stream
    const audioTrack = stream.getAudioTracks()[0]
    if (audioTrack) {
      try {
        const context = new AudioContext()
        meterContext.current = context
        const analyser = context.createAnalyser()
        analyser.fftSize = 256
        context.createMediaStreamSource(new MediaStream([audioTrack])).connect(analyser)
        const samples = new Uint8Array(analyser.fftSize)
        let lastUpdate = 0
        const sampleMic = (now: number) => {
          analyser.getByteTimeDomainData(samples)
          let sum = 0
          for (const sample of samples) { const amplitude = (sample - 128) / 128; sum += amplitude * amplitude }
          if (now - lastUpdate > 100) { setAudioLevel(Math.min(100, Math.round(Math.sqrt(sum / samples.length) * 250))); lastUpdate = now }
          meterFrame.current = requestAnimationFrame(sampleMic)
        }
        meterFrame.current = requestAnimationFrame(sampleMic)
      } catch { setAudioLevel(0) }
    }
    remoteStream.current = new MediaStream()
    if (remoteVideo.current) remoteVideo.current.srcObject = remoteStream.current
    const connection = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] })
    peer.current = connection
    stream.getTracks().forEach(track => connection.addTrack(track, stream))
    connection.onicecandidate = event => {
      if (event.candidate) {
        try { send('ICE_CANDIDATE', { candidate: event.candidate.toJSON() }) }
        catch (error) { setCallError(error instanceof Error ? error.message : 'Could not send a network candidate.') }
      }
    }
    connection.ontrack = event => {
      const target = remoteStream.current
      if (!target) return
      if (!target.getTracks().some(track => track.id === event.track.id)) target.addTrack(event.track)
      if (remoteVideo.current) remoteVideo.current.srcObject = target
    }
    connection.onconnectionstatechange = () => {
      if (connection.connectionState === 'failed' || connection.connectionState === 'closed') stopCall(false)
    }
    return connection
  }, [send, stopCall])

  const accept = useCallback(async () => {
    const currentOffer = offer.current
    if (!currentOffer) return
    setCallError('')
    try {
      const connection = await createPeer()
      await connection.setRemoteDescription(new RTCSessionDescription(currentOffer.payload as unknown as RTCSessionDescriptionInit))
      for (const candidate of pendingIce.current.splice(0)) await connection.addIceCandidate(new RTCIceCandidate(candidate))
      const answer = await connection.createAnswer()
      await connection.setLocalDescription(answer)
      send('ANSWER', { sdp: answer.sdp, type: answer.type })
      setIncoming(null); setActive(true)
    } catch (error) {
      stopCall(false)
      setCallError(error instanceof Error ? error.message : 'Could not accept the video call. Check camera and microphone permissions.')
    }
  }, [createPeer, send, stopCall])

  const start = useCallback(async () => {
    setCallError('')
    try {
      const connection = await createPeer()
      const createdOffer = await connection.createOffer()
      await connection.setLocalDescription(createdOffer)
      send('OFFER', { sdp: createdOffer.sdp, type: createdOffer.type })
      setActive(true)
    } catch (error) {
      stopCall(false)
      setCallError(error instanceof Error ? error.message : 'Could not start the video call. Check camera and microphone permissions.')
    }
  }, [createPeer, send, stopCall])

  useEffect(() => {
    if (!connected || !client?.connected || !attemptId) return
    const subscription = client.subscribe(`/topic/attempt/${attemptId}/webrtc`, (frame: IMessage) => {
      let event: Signal
      try { event = JSON.parse(frame.body) as Signal } catch { return }
      if (event.senderRole === role) return
      if (event.type === 'OFFER' && role === 'CANDIDATE') {
        offer.current = event
        setIncoming(event); setCallError('')
      } else if (event.type === 'ANSWER' && role === 'RECRUITER' && peer.current) {
        void peer.current.setRemoteDescription(new RTCSessionDescription(event.payload as unknown as RTCSessionDescriptionInit))
          .then(async () => { for (const candidate of pendingIce.current.splice(0)) await peer.current?.addIceCandidate(new RTCIceCandidate(candidate)) })
          .catch(error => setCallError(error instanceof Error ? error.message : 'Could not connect to the candidate.'))
      } else if (event.type === 'ICE_CANDIDATE') {
        const candidate = event.payload?.candidate as RTCIceCandidateInit | undefined
        if (!candidate) return
        if (peer.current?.remoteDescription) void peer.current.addIceCandidate(new RTCIceCandidate(candidate)).catch(() => {})
        else pendingIce.current.push(candidate)
      } else if (event.type === 'END_CALL') {
        stopCall(false)
      }
    })
    return () => subscription.unsubscribe()
  }, [attemptId, client, connected, role, stopCall])

  useEffect(() => {
    if (localVideo.current && localStream.current) localVideo.current.srcObject = localStream.current
    if (remoteVideo.current && remoteStream.current) remoteVideo.current.srcObject = remoteStream.current
  }, [active])

  useEffect(() => () => stopCall(true), [stopCall])

  function toggleMute() {
    const next = !muted
    localStream.current?.getAudioTracks().forEach(track => { track.enabled = !next })
    setMuted(next)
  }
  function toggleCamera() {
    const next = !cameraOff
    localStream.current?.getVideoTracks().forEach(track => { track.enabled = !next })
    setCameraOff(next)
  }

  return <>
    {role === 'RECRUITER' && <div className="live-call-controls">
      {!active ? <button type="button" className="button secondary" disabled={!connected} onClick={() => void start()}><Phone size={14}/> Start video call</button> : <>
        <div className="live-call-videos"><video ref={remoteVideo} autoPlay playsInline/><video ref={localVideo} autoPlay muted playsInline className="live-call-local"/></div>
        <div className="live-audio-meter" aria-label={`Microphone activity ${audioLevel} percent`}><span>Mic activity</span><i style={{ width: `${muted ? 0 : audioLevel}%` }}/></div><div className="live-call-buttons"><button type="button" className="button secondary" onClick={toggleMute}>{muted ? <MicOff size={14}/> : <Mic size={14}/>} {muted ? 'Unmute' : 'Mute'}</button><button type="button" className="button secondary" onClick={toggleCamera}>{cameraOff ? <CameraOff size={14}/> : <Camera size={14}/>} {cameraOff ? 'Turn camera on' : 'Turn camera off'}</button><button type="button" className="button danger" onClick={() => stopCall(true)}><PhoneOff size={14}/> End call</button></div>
      </>}
      {callError && <div className="alert error">{callError}</div>}
      {active && <p className="muted">Call connecting or live. The candidate must accept the request.</p>}
    </div>}
    {role === 'CANDIDATE' && <>
      {incoming && <div className="video-call-overlay" role="dialog" aria-modal="true" aria-label="Incoming recruiter video call"><div className="video-call-dialog"><h2>Incoming video call</h2><p>{incoming.senderName || 'The recruiter'} is requesting a live video session.</p><p className="muted">Your camera and microphone turn on only after you accept.</p><div><button type="button" className="button secondary" onClick={() => { try { send('END_CALL') } catch { /* ignore */ } offer.current = null; setIncoming(null) }}>Decline</button><button type="button" className="button primary" onClick={() => void accept()}><Phone size={14}/> Accept call</button></div>{callError && <div className="alert error">{callError}</div>}</div></div>}
      {active && <aside className="candidate-video-call" aria-label="Live recruiter video call"><div className="candidate-video-heading"><strong>Live recruiter call</strong><button type="button" aria-label="End call" onClick={() => stopCall(true)}><PhoneOff size={14}/></button></div><div className="live-call-videos"><video ref={remoteVideo} autoPlay playsInline/><video ref={localVideo} autoPlay muted playsInline className="live-call-local"/></div><div className="live-audio-meter" aria-label={`Microphone activity ${audioLevel} percent`}><span>Mic activity</span><i style={{ width: `${muted ? 0 : audioLevel}%` }}/></div><div className="live-call-buttons"><button type="button" className="button secondary" onClick={toggleMute}>{muted ? <MicOff size={14}/> : <Mic size={14}/>} {muted ? 'Unmute' : 'Mute'}</button><button type="button" className="button secondary" onClick={toggleCamera}>{cameraOff ? <CameraOff size={14}/> : <Camera size={14}/>} {cameraOff ? 'Camera on' : 'Camera off'}</button></div>{callError && <div className="alert error">{callError}</div>}</aside>}
    </>}
  </>
}
