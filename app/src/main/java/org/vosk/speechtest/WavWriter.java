package org.vosk.speechtest;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;


public class WavWriter implements Closeable {
    private final RandomAccessFile raf;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private long dataBytesWritten = 0;

    public WavWriter(File outWav, int sampleRate, int channels, int bitsPerSample) throws IOException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.raf = new RandomAccessFile(outWav, "rw");
        this.raf.setLength(0); // overwrite if exists
        writeHeaderPlaceholder();
    }

    // Append raw PCM bytes (little-endian) into WAV data chunk
    public void append(byte[] pcm, int len) throws IOException {
        raf.write(pcm, 0, len);
        dataBytesWritten += len;
    }

    @Override
    public void close() throws IOException {
        // Patch RIFF chunk size and data chunk size
        // RIFF size = 36 + dataBytes; data chunk size = dataBytes
        raf.seek(4);
        writeLEInt(36 + (int) dataBytesWritten);
        raf.seek(40);
        writeLEInt((int) dataBytesWritten);
        raf.close();
    }

    private void writeHeaderPlaceholder() throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        // RIFF header (44 bytes)
        raf.writeBytes("RIFF");
        writeLEInt(36);                // placeholder, will patch at close()
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeLEInt(16);                // PCM fmt chunk size
        writeLEShort((short) 1);       // AudioFormat = PCM
        writeLEShort((short) channels);
        writeLEInt(sampleRate);
        writeLEInt(byteRate);
        writeLEShort((short) blockAlign);
        writeLEShort((short) bitsPerSample);
        raf.writeBytes("data");
        writeLEInt(0);                 // data size placeholder
    }

    private void writeLEInt(int v) throws IOException {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
        raf.write((v >> 16) & 0xff);
        raf.write((v >> 24) & 0xff);
    }

    private void writeLEShort(short v) throws IOException {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
    }
}

