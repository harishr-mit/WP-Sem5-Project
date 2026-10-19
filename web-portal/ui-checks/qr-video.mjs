// Turns a PNG (the live QR from pass-service) into a short Y4M video that Chromium can use as a fake camera
// (--use-file-for-fake-video-capture). Node only: a minimal PNG decoder (8-bit, non-interlaced; grey, RGB, RGBA or
// palette) and the YUV 4:2:0 frames Y4M expects. The QR is centred on a white square with a quiet zone.
import { readFileSync, writeFileSync } from 'node:fs';
import { inflateSync } from 'node:zlib';

export function decodePng(buffer) {
    const signature = '89504e470d0a1a0a';
    if (buffer.subarray(0, 8).toString('hex') !== signature) {
        throw new Error('not a PNG');
    }
    let offset = 8;
    let width = 0, height = 0, depth = 0, type = 0, interlace = 0;
    let palette = null;
    const idat = [];
    while (offset < buffer.length) {
        const length = buffer.readUInt32BE(offset);
        const kind = buffer.toString('ascii', offset + 4, offset + 8);
        const data = buffer.subarray(offset + 8, offset + 8 + length);
        if (kind === 'IHDR') {
            width = data.readUInt32BE(0);
            height = data.readUInt32BE(4);
            depth = data[8];
            type = data[9];
            interlace = data[12];
        } else if (kind === 'PLTE') {
            palette = data;
        } else if (kind === 'IDAT') {
            idat.push(data);
        } else if (kind === 'IEND') {
            break;
        }
        offset += 12 + length;
    }
    if (depth !== 8 || interlace !== 0) {
        throw new Error(`unsupported PNG: depth ${depth}, interlace ${interlace}`);
    }
    const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[type];
    const raw = inflateSync(Buffer.concat(idat));
    const stride = width * channels;
    const pixels = Buffer.alloc(stride * height);
    for (let y = 0; y < height; y++) {
        const filter = raw[y * (stride + 1)];
        const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
        for (let x = 0; x < stride; x++) {
            const a = x >= channels ? pixels[y * stride + x - channels] : 0;
            const b = y > 0 ? pixels[(y - 1) * stride + x] : 0;
            const c = x >= channels && y > 0 ? pixels[(y - 1) * stride + x - channels] : 0;
            let v = line[x];
            if (filter === 1) v += a;
            else if (filter === 2) v += b;
            else if (filter === 3) v += Math.floor((a + b) / 2);
            else if (filter === 4) {
                const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
            }
            pixels[y * stride + x] = v & 0xff;
        }
    }
    // luma of each pixel (alpha over white)
    const luma = new Uint8Array(width * height);
    for (let i = 0; i < width * height; i++) {
        let r, g, bl, alpha = 255;
        if (type === 0) { r = g = bl = pixels[i]; }
        else if (type === 4) { r = g = bl = pixels[i * 2]; alpha = pixels[i * 2 + 1]; }
        else if (type === 3) { const k = pixels[i] * 3; r = palette[k]; g = palette[k + 1]; bl = palette[k + 2]; }
        else { r = pixels[i * channels]; g = pixels[i * channels + 1]; bl = pixels[i * channels + 2]; if (type === 6) alpha = pixels[i * 4 + 3]; }
        const y = 0.299 * r + 0.587 * g + 0.114 * bl;
        luma[i] = Math.round((y * alpha + 255 * (255 - alpha)) / 255);
    }
    return { width, height, luma };
}

/** Writes `frames` identical frames of `size`×`size`: the image scaled to 70% of the frame, white around it. */
export function writeY4m(png, file, size = 640, frames = 15) {
    const { width, height, luma } = png;
    const target = Math.floor(size * 0.7);
    const left = Math.floor((size - target) / 2);
    const y = new Uint8Array(size * size).fill(255);
    for (let row = 0; row < target; row++) {
        for (let col = 0; col < target; col++) {
            const sx = Math.floor((col * width) / target);
            const sy = Math.floor((row * height) / target);
            // hard threshold: a crisp black-and-white code, whatever the pass colour
            y[(row + left) * size + col + left] = luma[sy * width + sx] < 160 ? 0 : 255;
        }
    }
    const chroma = new Uint8Array((size / 2) * (size / 2)).fill(128);
    const header = Buffer.from(`YUV4MPEG2 W${size} H${size} F10:1 Ip A1:1 C420jpeg\n`, 'ascii');
    const frame = Buffer.concat([Buffer.from('FRAME\n', 'ascii'), Buffer.from(y), Buffer.from(chroma), Buffer.from(chroma)]);
    writeFileSync(file, Buffer.concat([header, ...Array(frames).fill(frame)]));
}

if (process.argv[1] && process.argv[1].endsWith('qr-video.mjs') && process.argv[3]) {
    writeY4m(decodePng(readFileSync(process.argv[2])), process.argv[3]);
    console.log('wrote', process.argv[3]);
}
