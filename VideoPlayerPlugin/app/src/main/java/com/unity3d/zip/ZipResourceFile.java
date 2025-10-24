package com.unity3d.zip;

import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Vector;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipResourceFile
{
  static final String LOG_TAG = "zipro";
  static final boolean LOGV = false;
  static final int kEOCDSignature = 101010256;
  static final int kEOCDLen = 22;
  static final int kEOCDNumEntries = 8;
  static final int kEOCDSize = 12;
  static final int kEOCDFileOffset = 16;
  static final int kMaxCommentLen = 65535;
  static final int kMaxEOCDSearch = 65557;
  static final int kLFHSignature = 67324752;
  static final int kLFHLen = 30;
  static final int kLFHNameLen = 26;
  static final int kLFHExtraLen = 28;
  static final int kCDESignature = 33639248;
  static final int kCDELen = 46;
  static final int kCDEMethod = 10;
  static final int kCDEModWhen = 12;
  static final int kCDECRC = 16;
  static final int kCDECompLen = 20;
  static final int kCDEUncompLen = 24;
  static final int kCDENameLen = 28;
  static final int kCDEExtraLen = 30;
  static final int kCDECommentLen = 32;
  static final int kCDELocalOffset = 42;
  static final int kCompressStored = 0;
  static final int kCompressDeflated = 8;
  static final int kZipEntryAdj = 10000;
  
  private static int swapEndian(int i)
  {
    return ((i & 0xFF) << 24) + ((i & 0xFF00) << 8) + ((i & 0xFF0000) >>> 8) + (i >>> 24 & 0xFF);
  }
  
  //private static int swapEndian(short i)
  //{
  //  return (i & 0xFF) << 8 | (i & 0xFF00) >>> 8;
 // }
  
  public static final class ZipEntryRO
  {
    public final File mFile;
    public final String mFileName;
    public final String mZipFileName;
    public long mLocalHdrOffset;
    public int mMethod;
    public long mWhenModified;
    public long mCRC32;
    public long mCompressedLength;
    public long mUncompressedLength;
    
    public ZipEntryRO(String zipFileName, File file, String fileName)
    {
      this.mFileName = fileName;
      this.mZipFileName = zipFileName;
      this.mFile = file;
    }
    
    public long mOffset = -1L;
    
    public void setOffsetFromFile(RandomAccessFile f, ByteBuffer buf)
      throws IOException
    {
      long localHdrOffset = this.mLocalHdrOffset;
      try
      {
        f.seek(localHdrOffset);
        f.readFully(buf.array());
        if (buf.getInt(0) != 67324752)
        {
          Log.w("zipro", "didn't find signature at start of lfh");
          throw new IOException();
        }
        int nameLen = buf.getShort(26) & 0xFFFF;
        int extraLen = buf.getShort(28) & 0xFFFF;
        this.mOffset = (localHdrOffset + 30L + nameLen + extraLen);
      }
      catch (FileNotFoundException e)
      {
        e.printStackTrace();
      }
      catch (IOException ioe)
      {
        ioe.printStackTrace();
      }
    }
    
    public long getOffset()
    {
      return this.mOffset;
    }
    
    public boolean isUncompressed()
    {
      return this.mMethod == 0;
    }
    
    public AssetFileDescriptor getAssetFileDescriptor()
    {
      if (this.mMethod == 0) {
        try
        {
          ParcelFileDescriptor pfd = ParcelFileDescriptor.open(this.mFile, 268435456);
          return new AssetFileDescriptor(pfd, getOffset(), this.mUncompressedLength);
        }
        catch (FileNotFoundException e)
        {
          e.printStackTrace();
        }
      }
      return null;
    }
    
    public String getZipFileName()
    {
      return this.mZipFileName;
    }
    
    public File getZipFile()
    {
      return this.mFile;
    }
  }
  
  private HashMap<String, ZipEntryRO> mHashMap = new HashMap<String, ZipEntryRO>();
  public HashMap<File, ZipFile> mZipFiles = new HashMap<File, ZipFile>();
  
  public ZipResourceFile(String zipFileName)
    throws IOException
  {
    addPatchFile(zipFileName);
  }
  
  ZipEntryRO[] getEntriesAt(String path)
  {
    Vector<ZipEntryRO> zev = new Vector<ZipEntryRO>();
    Collection<ZipEntryRO> values = this.mHashMap.values();
    if (path == null) {
      path = "";
    }
    int length = path.length();
    for (ZipEntryRO ze : values) {
      if ((ze.mFileName.startsWith(path)) && 
        (-1 == ze.mFileName.indexOf('/', length))) {
        zev.add(ze);
      }
    }
    ZipEntryRO[] entries = new ZipEntryRO[zev.size()];
    return (ZipEntryRO[])zev.toArray(entries);
  }
  
  public ZipEntryRO[] getAllEntries()
  {
    Collection<ZipEntryRO> values = this.mHashMap.values();
    return (ZipEntryRO[])values.toArray(new ZipEntryRO[values.size()]);
  }
  
  public AssetFileDescriptor getAssetFileDescriptor(String assetPath)
  {
    ZipEntryRO entry = (ZipEntryRO)this.mHashMap.get(assetPath);
    if (entry != null) {
      return entry.getAssetFileDescriptor();
    }
    return null;
  }
  
  public InputStream getInputStream(String assetPath)
    throws IOException
  {
    ZipEntryRO entry = (ZipEntryRO)this.mHashMap.get(assetPath);
    if (entry != null)
    {
      if (entry.isUncompressed()) {
        return entry.getAssetFileDescriptor().createInputStream();
      }
      ZipFile zf = (ZipFile)this.mZipFiles.get(entry.getZipFile());
      if (zf == null)
      {
        zf = new ZipFile(entry.getZipFile(), 1);
        this.mZipFiles.put(entry.getZipFile(), zf);
      }
      ZipEntry zi = zf.getEntry(assetPath);
      if (zi != null) {
        return zf.getInputStream(zi);
      }
    }
    return null;
  }
  
  public ZipEntry getZipEntry(String assetPath)
    throws IOException
  {
    ZipEntryRO entry = (ZipEntryRO)this.mHashMap.get(assetPath);
    if (entry != null)
    {
      ZipFile zf = (ZipFile)this.mZipFiles.get(entry.getZipFile());
      if (zf == null)
      {
        zf = new ZipFile(entry.getZipFile(), 1);
        this.mZipFiles.put(entry.getZipFile(), zf);
      }
      ZipEntry zi = zf.getEntry(assetPath);
      return zi;
    }
    return null;
  }
  
  ByteBuffer mLEByteBuffer = ByteBuffer.allocate(4);
  
  private static int read4LE(RandomAccessFile f)
    throws EOFException, IOException
  {
    return swapEndian(f.readInt());
  }
  
  void addPatchFile(String zipFileName)
    throws IOException
  {
    File file = new File(zipFileName);
    RandomAccessFile f = new RandomAccessFile(file, "r");
    long fileLength = f.length();
    if (fileLength < 22L) {
      throw new IOException();
    }
    long readAmount = 65557L;
    if (readAmount > fileLength) {
      readAmount = fileLength;
    }
    f.seek(0L);
    
    int header = read4LE(f);
    if (header == 101010256)
    {
      Log.i("zipro", "Found Zip archive, but it looks empty");
      throw new IOException();
    }
    if (header != 67324752)
    {
      Log.v("zipro", "Not a Zip archive");
      throw new IOException();
    }
    long searchStart = fileLength - readAmount;
    
    f.seek(searchStart);
    ByteBuffer bbuf = ByteBuffer.allocate((int)readAmount);
    byte[] buffer = bbuf.array();
    f.readFully(buffer);
    bbuf.order(ByteOrder.LITTLE_ENDIAN);
    int eocdIdx;
    for ( eocdIdx = buffer.length - 22; eocdIdx >= 0; eocdIdx--) {
      if ((buffer[eocdIdx] == 80) && (bbuf.getInt(eocdIdx) == 101010256)) {
        break;
      }
    }
    if (eocdIdx < 0) {
      Log.d("zipro", "Zip: EOCD not found, " + zipFileName + " is not zip");
    }
    int numEntries = bbuf.getShort(eocdIdx + 8);
    long dirSize = bbuf.getInt(eocdIdx + 12) & 0xFFFFFFFF;
    long dirOffset = bbuf.getInt(eocdIdx + 16) & 0xFFFFFFFF;
    if (dirOffset + dirSize > fileLength)
    {
      Log.w("zipro", "bad offsets (dir " + dirOffset + ", size " + dirSize + ", eocd " + eocdIdx + ")");
      throw new IOException();
    }
    if (numEntries == 0)
    {
      Log.w("zipro", "empty archive?");
      throw new IOException();
    }
    MappedByteBuffer directoryMap = f.getChannel().map(FileChannel.MapMode.READ_ONLY, dirOffset, dirSize);
    directoryMap.order(ByteOrder.LITTLE_ENDIAN);
    
    byte[] tempBuf = new byte[65535];
    
    int currentOffset = 0;
    
    ByteBuffer buf = ByteBuffer.allocate(30);
    buf.order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < numEntries; i++)
    {
      if (directoryMap.getInt(currentOffset) != 33639248)
      {
        Log.w("zipro", "Missed a central dir sig (at " + currentOffset + ")");
        throw new IOException();
      }
      int fileNameLen = directoryMap.getShort(currentOffset + 28) & 0xFFFF;
      int extraLen = directoryMap.getShort(currentOffset + 30) & 0xFFFF;
      int commentLen = directoryMap.getShort(currentOffset + 32) & 0xFFFF;
      
      directoryMap.position(currentOffset + 46);
      directoryMap.get(tempBuf, 0, fileNameLen);
      directoryMap.position(0);
      
      String str = new String(tempBuf, 0, fileNameLen);
      
      ZipEntryRO ze = new ZipEntryRO(zipFileName, file, str);
      ze.mMethod = (directoryMap.getShort(currentOffset + 10) & 0xFFFF);
      ze.mWhenModified = (directoryMap.getInt(currentOffset + 12) & 0xFFFFFFFF);
      ze.mCRC32 = (directoryMap.getLong(currentOffset + 16) & 0xFFFFFFFF);
      ze.mCompressedLength = (directoryMap.getLong(currentOffset + 20) & 0xFFFFFFFF);
      ze.mUncompressedLength = (directoryMap.getLong(currentOffset + 24) & 0xFFFFFFFF);
      ze.mLocalHdrOffset = (directoryMap.getInt(currentOffset + 42) & 0xFFFFFFFF);
      
      buf.clear();
      ze.setOffsetFromFile(f, buf);
      
      this.mHashMap.put(str, ze);
      
      currentOffset += 46 + fileNameLen + extraLen + commentLen;
    }
  }
}
