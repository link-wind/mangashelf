/// <reference types="@webgpu/types" />
import type { Anime4KPipeline } from 'anime4k-webgpu'

export type SrMode = 'off' | 'fast' | 'quality'
export type SrBackend = 'webgpu' | 'webgl2'

export interface SrStats {
  backend: SrBackend
  mode: Exclude<SrMode, 'off'>
  sourceWidth: number
  sourceHeight: number
  outputWidth: number
  outputHeight: number
  avgFrameMs: number
}

export interface SrController {
  setSplitRatio: (ratio: number | null) => void
  destroy: () => void
}

export interface StartSrOptions {
  video: HTMLVideoElement
  canvas: HTMLCanvasElement
  mode: Exclude<SrMode, 'off'>
  splitRatio: number | null
  onStats?: (stats: SrStats) => void
  onError?: (code: 'cors' | 'unsupported' | 'runtime', message: string) => void
  onAutoDowngrade?: (message: string) => void
}

const SR_MODE_KEY = 'ms-player-sr:v1'

export function loadSrMode(): SrMode {
  try {
    const raw = localStorage.getItem(SR_MODE_KEY)
    if (raw === 'off' || raw === 'fast' || raw === 'quality') return raw
  } catch { /* ignore */ }
  return 'off'
}

export function saveSrMode(mode: SrMode) {
  try {
    localStorage.setItem(SR_MODE_KEY, mode)
  } catch { /* ignore */ }
}

export function isWebGpuAvailable(): boolean {
  return typeof navigator !== 'undefined' && 'gpu' in navigator && Boolean(navigator.gpu)
}

// 懒加载 anime4k-webgpu（约 3.4MB）：未开启超清时不下载该 chunk
let anime4kModPromise: Promise<typeof import('anime4k-webgpu')> | null = null
function loadAnime4K() {
  if (!anime4kModPromise) {
    anime4kModPromise = import('anime4k-webgpu')
  }
  return anime4kModPromise
}

// 共享单个 GPUDevice，避免切档位/切集反复创建导致显存泄漏
let sharedDevicePromise: Promise<GPUDevice | null> | null = null
async function getSharedGpuDevice(): Promise<GPUDevice | null> {
  if (!isWebGpuAvailable()) return null
  if (!sharedDevicePromise) {
    sharedDevicePromise = (async () => {
      try {
        const adapter = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' })
          ?? await navigator.gpu.requestAdapter()
        if (!adapter) return null
        const device = await adapter.requestDevice()
        void device.lost.then(() => {
          sharedDevicePromise = null
        })
        return device
      } catch {
        sharedDevicePromise = null
        return null
      }
    })()
  }
  return sharedDevicePromise
}

/** 计算封顶后的输出分辨率：保持视频宽高比，最多 2× 源分辨率且不超过 4K（3840×2160）。 */
function computeTargetSize(
  srcW: number,
  srcH: number,
  canvas: HTMLCanvasElement,
  mode: Exclude<SrMode, 'off'>,
): { width: number; height: number } {
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const rect = canvas.getBoundingClientRect()
  const boxW = Math.max(srcW, Math.round((rect.width || srcW) * dpr))
  const boxH = Math.max(srcH, Math.round((rect.height || srcH) * dpr))

  // 质量档允许最高 2× 放大以充分呈现 Anime4K CNNx2 细节；效率档在源已 ≥1080p 时以去噪修线为主
  const maxScale = mode === 'quality' ? 2 : (srcW >= 1920 ? 1.25 : 2)
  const desiredScale = Math.min(
    maxScale,
    Math.max(1.25, Math.min(boxW / srcW, boxH / srcH)),
    3840 / srcW,
    2160 / srcH,
  )
  const width = Math.max(2, Math.round((srcW * desiredScale) / 2) * 2)
  const height = Math.max(2, Math.round((srcH * desiredScale) / 2) * 2)
  return { width, height }
}

function isSecurityLikeError(err: unknown): boolean {
  if (!err) return false
  if (err instanceof DOMException && err.name === 'SecurityError') return true
  const msg = err instanceof Error ? err.message : String(err)
  return /tainted|cross-?origin|securityerror|origin-clean/i.test(msg)
}

// ── WebGPU Blit Shader（同时支持全屏超清与原画/超清实时分屏对比）──
const WEBGPU_BLIT_WGSL = /* wgsl */ `
struct VsOut {
  @builtin(position) pos: vec4f,
  @location(0) uv: vec2f,
};

struct SplitUniform {
  splitX: f32,
  _pad0: f32,
  _pad1: f32,
  _pad2: f32,
};

@vertex
fn vsMain(@builtin(vertex_index) idx: u32) -> VsOut {
  var positions = array<vec2f, 6>(
    vec2f(-1.0, -1.0), vec2f( 1.0, -1.0), vec2f(-1.0,  1.0),
    vec2f(-1.0,  1.0), vec2f( 1.0, -1.0), vec2f( 1.0,  1.0)
  );
  var uvs = array<vec2f, 6>(
    vec2f(0.0, 1.0), vec2f(1.0, 1.0), vec2f(0.0, 0.0),
    vec2f(0.0, 0.0), vec2f(1.0, 1.0), vec2f(1.0, 0.0)
  );
  var out: VsOut;
  out.pos = vec4f(positions[idx], 0.0, 1.0);
  out.uv = uvs[idx];
  return out;
}

@group(0) @binding(0) var linearSampler: sampler;
@group(0) @binding(1) var originalTex: texture_2d<f32>;
@group(0) @binding(2) var enhancedTex: texture_2d<f32>;
@group(0) @binding(3) var<uniform> splitCfg: SplitUniform;

@fragment
fn fsMain(in: VsOut) -> @location(0) vec4f {
  let orig = textureSample(originalTex, linearSampler, in.uv);
  let enh = textureSample(enhancedTex, linearSampler, in.uv);
  if (splitCfg.splitX >= 0.0 && in.uv.x < splitCfg.splitX) {
    return orig;
  }
  return enh;
}
`

// ── WebGL2 兜底 Shader：AMD FidelityFX CAS 启发式对比度自适应锐化（支持分屏对比）──
const WEBGL2_VS = `#version 300 es
out vec2 vUv;
void main() {
  vec2 pos[6] = vec2[6](
    vec2(-1.0, -1.0), vec2( 1.0, -1.0), vec2(-1.0,  1.0),
    vec2(-1.0,  1.0), vec2( 1.0, -1.0), vec2( 1.0,  1.0)
  );
  vec2 p = pos[gl_VertexID];
  vUv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5);
  gl_Position = vec4(p, 0.0, 1.0);
}
`

const WEBGL2_CAS_FS = `#version 300 es
precision highp float;
in vec2 vUv;
out vec4 outColor;
uniform sampler2D uTex;
uniform vec2 uTexelSize;
uniform float uSharpness;
uniform float uSplitX;

void main() {
  vec3 e = texture(uTex, vUv).rgb;
  if (uSplitX >= 0.0 && vUv.x < uSplitX) {
    outColor = vec4(e, 1.0);
    return;
  }
  vec3 b = texture(uTex, vUv + vec2(0.0, -uTexelSize.y)).rgb;
  vec3 d = texture(uTex, vUv + vec2(-uTexelSize.x, 0.0)).rgb;
  vec3 f = texture(uTex, vUv + vec2(uTexelSize.x, 0.0)).rgb;
  vec3 h = texture(uTex, vUv + vec2(0.0, uTexelSize.y)).rgb;

  vec3 mn = min(min(min(d, e), min(f, b)), h);
  vec3 mx = max(max(max(d, e), max(f, b)), h);
  vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0);
  amp = sqrt(amp);
  vec3 w = amp * (-mix(0.125, 0.2, uSharpness));
  vec3 res = (e + (b + d + f + h) * w) / (1.0 + 4.0 * w);
  outColor = vec4(clamp(res, 0.0, 1.0), 1.0);
}
`

type VideoFrameCallbackVideo = HTMLVideoElement & {
  requestVideoFrameCallback?: (cb: (now: DOMHighResTimeStamp, metadata: unknown) => void) => number
  cancelVideoFrameCallback?: (handle: number) => void
}

function scheduleNextFrame(
  video: HTMLVideoElement,
  cb: () => void,
): { cancel: () => void } {
  const v = video as VideoFrameCallbackVideo
  if (typeof v.requestVideoFrameCallback === 'function') {
    const id = v.requestVideoFrameCallback(() => cb())
    return { cancel: () => v.cancelVideoFrameCallback?.(id) }
  }
  const id = window.requestAnimationFrame(() => cb())
  return { cancel: () => window.cancelAnimationFrame(id) }
}

/**
 * 启动实时视频超清渲染管线：
 * - 优先 WebGPU + Anime4K（对齐 Kazumi 效率档/质量档）；
 * - 不支持 WebGPU 时自动降级到 WebGL2 CAS 对比度自适应锐化；
 * - 支持热切分屏对比、暂停态 seek 刷新、分辨率变化重建纹理、CORS 错误回退与掉帧保护。
 */
export async function startSuperResolution(opts: StartSrOptions): Promise<SrController> {
  const { video, canvas, mode, onStats, onError, onAutoDowngrade } = opts
  let splitRatio = opts.splitRatio
  let destroyed = false
  let pendingFrame: { cancel: () => void } | null = null
  let hasUploadedFrame = false
  let downgraded = false

  // 等待视频拿到真实宽高
  if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || video.videoWidth === 0) {
    await new Promise<void>((resolve) => {
      const done = () => {
        video.removeEventListener('loadeddata', done)
        video.removeEventListener('loadedmetadata', done)
        resolve()
      }
      video.addEventListener('loadeddata', done, { once: true })
      video.addEventListener('loadedmetadata', done, { once: true })
    })
  }
  if (destroyed) {
    return { setSplitRatio: () => {}, destroy: () => {} }
  }

  const device = await getSharedGpuDevice()
  if (destroyed) {
    return { setSplitRatio: () => {}, destroy: () => {} }
  }

  if (device) {
    try {
      return await startWebGpuLoop(device)
    } catch (err) {
      if (isSecurityLikeError(err)) {
        onError?.('cors', '当前线路受跨域限制，无法开启超清，已切回原画')
        throw err
      }
      // WebGPU 初始化异常时尝试降级到 WebGL2
    }
  }

  return startWebGl2Fallback()

  async function startWebGpuLoop(gpuDevice: GPUDevice): Promise<SrController> {
    const a4k = await loadAnime4K()
    if (destroyed) return { setSplitRatio: () => {}, destroy: () => {} }

    const ctx = canvas.getContext('webgpu')
    if (!ctx) throw new Error('WebGPU canvas context unavailable')
    const canvasFormat = navigator.gpu.getPreferredCanvasFormat()
    ctx.configure({ device: gpuDevice, format: canvasFormat, alphaMode: 'opaque' })

    const sampler = gpuDevice.createSampler({ magFilter: 'linear', minFilter: 'linear' })
    const splitBuf = gpuDevice.createBuffer({
      size: 16,
      usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST,
    })
    const writeSplit = () => {
      gpuDevice.queue.writeBuffer(splitBuf, 0, new Float32Array([splitRatio ?? -1, 0, 0, 0]))
    }
    writeSplit()

    const blitModule = gpuDevice.createShaderModule({ code: WEBGPU_BLIT_WGSL })
    const blitBindLayout = gpuDevice.createBindGroupLayout({
      entries: [
        { binding: 0, visibility: GPUShaderStage.FRAGMENT, sampler: {} },
        { binding: 1, visibility: GPUShaderStage.FRAGMENT, texture: {} },
        { binding: 2, visibility: GPUShaderStage.FRAGMENT, texture: {} },
        { binding: 3, visibility: GPUShaderStage.FRAGMENT, buffer: { type: 'uniform' } },
      ],
    })
    const blitPipeline = gpuDevice.createRenderPipeline({
      layout: gpuDevice.createPipelineLayout({ bindGroupLayouts: [blitBindLayout] }),
      vertex: { module: blitModule, entryPoint: 'vsMain' },
      fragment: { module: blitModule, entryPoint: 'fsMain', targets: [{ format: canvasFormat }] },
      primitive: { topology: 'triangle-list' },
    })

    let srcW = 0
    let srcH = 0
    let outW = 0
    let outH = 0
    let inputTexture: GPUTexture | null = null
    let pipelines: Anime4KPipeline[] = []
    let blitBindGroup: GPUBindGroup | null = null

    const rebuildPipelines = () => {
      const vw = video.videoWidth
      const vh = video.videoHeight
      if (!vw || !vh) return false
      const target = computeTargetSize(vw, vh, canvas, mode)
      if (inputTexture && vw === srcW && vh === srcH && target.width === outW && target.height === outH) {
        return true
      }
      inputTexture?.destroy()
      srcW = vw
      srcH = vh
      outW = target.width
      outH = target.height
      canvas.width = outW
      canvas.height = outH

      inputTexture = gpuDevice.createTexture({
        size: [srcW, srcH, 1],
        format: 'rgba16float',
        usage:
          GPUTextureUsage.TEXTURE_BINDING |
          GPUTextureUsage.COPY_DST |
          GPUTextureUsage.RENDER_ATTACHMENT,
      })

      if (mode === 'fast') {
        // 对标 Kazumi 效率档：ClampHighlights → CNNM（轻量修复）→（需要放大时）CNNx2M
        const list: Anime4KPipeline[] = []
        let cur: GPUTexture = inputTexture
        const clamp = new a4k.ClampHighlights({ device: gpuDevice, inputTexture: cur })
        list.push(clamp)
        cur = clamp.getOutputTexture()
        const restore = new a4k.CNNM({ device: gpuDevice, inputTexture: cur })
        list.push(restore)
        cur = restore.getOutputTexture()
        if (outW > srcW * 1.15 && outH > srcH * 1.15) {
          const up = new a4k.CNNx2M({ device: gpuDevice, inputTexture: cur })
          list.push(up)
          cur = up.getOutputTexture()
        }
        pipelines = list
      } else {
        // 对标 Kazumi 质量档：Anime4K ModeA（ClampHighlights → CNNVL → CNNx2VL → 自适应缩放）
        const preset = new a4k.ModeA({
          device: gpuDevice,
          inputTexture,
          nativeDimensions: { width: srcW, height: srcH },
          targetDimensions: { width: outW, height: outH },
        })
        pipelines = [preset]
      }

      const finalTex = pipelines[pipelines.length - 1].getOutputTexture()
      blitBindGroup = gpuDevice.createBindGroup({
        layout: blitBindLayout,
        entries: [
          { binding: 0, resource: sampler },
          { binding: 1, resource: inputTexture.createView() },
          { binding: 2, resource: finalTex.createView() },
          { binding: 3, resource: { buffer: splitBuf } },
        ],
      })
      hasUploadedFrame = false
      return true
    }

    const frameTimes: number[] = []
    let lastStatAt = 0

    const renderOnce = (uploadVideo: boolean) => {
      if (destroyed) return
      if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || video.videoWidth === 0) return
      if (!rebuildPipelines() || !inputTexture || !blitBindGroup) return

      const t0 = performance.now()
      if (uploadVideo || !hasUploadedFrame) {
        try {
          gpuDevice.queue.copyExternalImageToTexture(
            { source: video },
            { texture: inputTexture },
            [srcW, srcH],
          )
          hasUploadedFrame = true
        } catch (err) {
          if (isSecurityLikeError(err)) {
            cleanup()
            onError?.('cors', '当前线路受跨域限制，无法开启超清，已切回原画')
            return
          }
          cleanup()
          onError?.('runtime', '超清渲染失败，已切回原画')
          return
        }
      }

      const encoder = gpuDevice.createCommandEncoder()
      for (const p of pipelines) {
        p.pass(encoder)
      }
      const pass = encoder.beginRenderPass({
        colorAttachments: [
          {
            view: ctx.getCurrentTexture().createView(),
            clearValue: { r: 0, g: 0, b: 0, a: 1 },
            loadOp: 'clear',
            storeOp: 'store',
          },
        ],
      })
      pass.setPipeline(blitPipeline)
      pass.setBindGroup(0, blitBindGroup)
      pass.draw(6)
      pass.end()
      gpuDevice.queue.submit([encoder.finish()])

      const dt = performance.now() - t0
      if (uploadVideo) {
        frameTimes.push(dt)
        if (frameTimes.length > 30) frameTimes.shift()
        const now = performance.now()
        if (now - lastStatAt > 600) {
          lastStatAt = now
          const avg = frameTimes.reduce((a, b) => a + b, 0) / frameTimes.length
          onStats?.({
            backend: 'webgpu',
            mode,
            sourceWidth: srcW,
            sourceHeight: srcH,
            outputWidth: outW,
            outputHeight: outH,
            avgFrameMs: Math.round(avg * 10) / 10,
          })
          // 连续 30 帧平均提交+主线程耗时严重超标时提醒降级
          if (!downgraded && mode === 'quality' && frameTimes.length >= 25 && avg > 42) {
            downgraded = true
            onAutoDowngrade?.('当前设备负载较高，已自动降档至「效率」超清')
          }
        }
      }
    }

    const tick = () => {
      if (destroyed) return
      renderOnce(true)
      if (!destroyed) {
        pendingFrame = scheduleNextFrame(video, tick)
      }
    }

    const onRefresh = () => renderOnce(true)
    const onResize = () => {
      if (rebuildPipelines()) renderOnce(true)
    }

    video.addEventListener('seeked', onRefresh)
    video.addEventListener('loadeddata', onRefresh)
    video.addEventListener('resize', onResize)
    window.addEventListener('resize', onResize)

    // 先立刻刷首帧，再进入帧回调循环
    renderOnce(true)
    if (!destroyed) {
      pendingFrame = scheduleNextFrame(video, tick)
    }

    function cleanup() {
      if (destroyed) return
      destroyed = true
      pendingFrame?.cancel()
      pendingFrame = null
      video.removeEventListener('seeked', onRefresh)
      video.removeEventListener('loadeddata', onRefresh)
      video.removeEventListener('resize', onResize)
      window.removeEventListener('resize', onResize)
      inputTexture?.destroy()
      splitBuf.destroy()
      inputTexture = null
      pipelines = []
      blitBindGroup = null
    }

    return {
      setSplitRatio(ratio) {
        splitRatio = ratio
        if (destroyed) return
        writeSplit()
        renderOnce(false)
      },
      destroy: cleanup,
    }
  }

  function startWebGl2Fallback(): SrController {
    const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, preserveDrawingBuffer: false })
    if (!gl) {
      onError?.('unsupported', '当前浏览器不支持 WebGPU / WebGL2，无法开启超清')
      return { setSplitRatio: () => {}, destroy: () => {} }
    }

    const compile = (type: number, source: string) => {
      const sh = gl.createShader(type)
      if (!sh) throw new Error('shader alloc failed')
      gl.shaderSource(sh, source)
      gl.compileShader(sh)
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
        const log = gl.getShaderInfoLog(sh)
        gl.deleteShader(sh)
        throw new Error(log || 'shader compile failed')
      }
      return sh
    }

    const vs = compile(gl.VERTEX_SHADER, WEBGL2_VS)
    const fs = compile(gl.FRAGMENT_SHADER, WEBGL2_CAS_FS)
    const program = gl.createProgram()!
    gl.attachShader(program, vs)
    gl.attachShader(program, fs)
    gl.linkProgram(program)
    gl.deleteShader(vs)
    gl.deleteShader(fs)

    const tex = gl.createTexture()!
    gl.bindTexture(gl.TEXTURE_2D, tex)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)

    const uTexelLoc = gl.getUniformLocation(program, 'uTexelSize')
    const uSharpLoc = gl.getUniformLocation(program, 'uSharpness')
    const uSplitLoc = gl.getUniformLocation(program, 'uSplitX')
    const sharpness = mode === 'quality' ? 0.92 : 0.62

    const frameTimes: number[] = []
    let lastStatAt = 0

    const renderOnce = (uploadVideo: boolean) => {
      if (destroyed) return
      const vw = video.videoWidth
      const vh = video.videoHeight
      if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || !vw || !vh) return

      const target = computeTargetSize(vw, vh, canvas, mode)
      if (canvas.width !== target.width || canvas.height !== target.height) {
        canvas.width = target.width
        canvas.height = target.height
      }

      const t0 = performance.now()
      gl.bindTexture(gl.TEXTURE_2D, tex)
      if (uploadVideo || !hasUploadedFrame) {
        try {
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, video)
          hasUploadedFrame = true
        } catch (err) {
          if (isSecurityLikeError(err)) {
            cleanup()
            onError?.('cors', '当前线路受跨域限制，无法开启超清，已切回原画')
            return
          }
          cleanup()
          onError?.('runtime', '超清渲染失败，已切回原画')
          return
        }
      }

      gl.viewport(0, 0, canvas.width, canvas.height)
      gl.useProgram(program)
      gl.uniform2f(uTexelLoc, 1 / vw, 1 / vh)
      gl.uniform1f(uSharpLoc, sharpness)
      gl.uniform1f(uSplitLoc, splitRatio ?? -1)
      gl.drawArrays(gl.TRIANGLES, 0, 6)

      const dt = performance.now() - t0
      if (uploadVideo) {
        frameTimes.push(dt)
        if (frameTimes.length > 30) frameTimes.shift()
        const now = performance.now()
        if (now - lastStatAt > 600) {
          lastStatAt = now
          const avg = frameTimes.reduce((a, b) => a + b, 0) / frameTimes.length
          onStats?.({
            backend: 'webgl2',
            mode,
            sourceWidth: vw,
            sourceHeight: vh,
            outputWidth: canvas.width,
            outputHeight: canvas.height,
            avgFrameMs: Math.round(avg * 10) / 10,
          })
        }
      }
    }

    const tick = () => {
      if (destroyed) return
      renderOnce(true)
      if (!destroyed) pendingFrame = scheduleNextFrame(video, tick)
    }

    const onRefresh = () => renderOnce(true)
    video.addEventListener('seeked', onRefresh)
    video.addEventListener('loadeddata', onRefresh)
    video.addEventListener('resize', onRefresh)

    renderOnce(true)
    if (!destroyed) pendingFrame = scheduleNextFrame(video, tick)

    function cleanup() {
      if (destroyed) return
      destroyed = true
      pendingFrame?.cancel()
      pendingFrame = null
      video.removeEventListener('seeked', onRefresh)
      video.removeEventListener('loadeddata', onRefresh)
      video.removeEventListener('resize', onRefresh)
      gl?.deleteTexture(tex)
      gl?.deleteProgram(program)
    }

    return {
      setSplitRatio(ratio) {
        splitRatio = ratio
        if (!destroyed) renderOnce(false)
      },
      destroy: cleanup,
    }
  }
}
