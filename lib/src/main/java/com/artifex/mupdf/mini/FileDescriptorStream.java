package com.artifex.mupdf.mini;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import com.artifex.mupdf.fitz.SeekableInputOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class FileDescriptorStream implements SeekableInputOutputStream {
    private final ParcelFileDescriptor pfd;
    private final FileDescriptor fd;
    private final FileInputStream in;
    private final FileOutputStream out;

    public FileDescriptorStream(ParcelFileDescriptor pfd) {
        this.pfd = pfd;
        this.fd = pfd.getFileDescriptor();
        this.in = new FileInputStream(fd);
        this.out = new FileOutputStream(fd);
    }

    @Override
    public int read(byte[] bytes) throws IOException {
        return in.read(bytes);
    }

    @Override
    public void write(byte[] bytes, int offset, int len) throws IOException {
        out.write(bytes, offset, len);
    }

    @Override
    public long seek(long offset, int whence) throws IOException {
        int nativeWhence;
        switch (whence) {
            case SEEK_SET: nativeWhence = OsConstants.SEEK_SET; break;
            case SEEK_CUR: nativeWhence = OsConstants.SEEK_CUR; break;
            case SEEK_END: nativeWhence = OsConstants.SEEK_END; break;
            default: throw new IllegalArgumentException("Invalid whence: " + whence);
        }
        try {
            return Os.lseek(fd, offset, nativeWhence);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    @Override
    public long position() throws IOException {
        try {
            return Os.lseek(fd, 0, OsConstants.SEEK_CUR);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void truncate() throws IOException {
        try {
            long pos = position();
            Os.ftruncate(fd, pos);
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    public void close() throws IOException {
        pfd.close();
    }
}
