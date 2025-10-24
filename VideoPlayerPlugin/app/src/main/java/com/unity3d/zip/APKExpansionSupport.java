package com.unity3d.zip;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.IOException;
import java.util.Vector;

public class APKExpansionSupport
{
  //private static final String EXP_PATH = "/Android/obb/";
  
  static String[] getAPKExpansionFiles(Context ctx, int mainVersion, int patchVersion)
  {
    String packageName = ctx.getPackageName();
    Vector<String> ret = new Vector<String>();
    if (Environment.getExternalStorageState().equals("mounted"))
    {
      File root = Environment.getExternalStorageDirectory();
      File expPath = new File(root.toString() + "/Android/obb/" + packageName);
      if (expPath.exists())
      {
        if (mainVersion > 0)
        {
          String strMainPath = expPath + File.separator + "main." + mainVersion + "." + packageName + ".obb";
          File main = new File(strMainPath);
          if (main.isFile()) {
            ret.add(strMainPath);
          }
        }
        if (patchVersion > 0)
        {
          String strPatchPath = expPath + File.separator + "patch." + mainVersion + "." + packageName + ".obb";
          File main = new File(strPatchPath);
          if (main.isFile()) {
            ret.add(strPatchPath);
          }
        }
      }
    }
    String[] retArray = new String[ret.size()];
    ret.toArray(retArray);
    return retArray;
  }
  
  public static ZipResourceFile getResourceZipFile(String[] expansionFiles)
    throws IOException
  {
    ZipResourceFile apkExpansionFile = null;
    String[] arrayOfString = expansionFiles;int j = expansionFiles.length;
    for (int i = 0; i < j; i++)
    {
      String expansionFilePath = arrayOfString[i];
      if (apkExpansionFile == null) {
        apkExpansionFile = new ZipResourceFile(expansionFilePath);
      } else {
        apkExpansionFile.addPatchFile(expansionFilePath);
      }
    }
    return apkExpansionFile;
  }
  
  public static ZipResourceFile getAPKExpansionZipFile(Context ctx, int mainVersion, int patchVersion)
    throws IOException
  {
    String[] expansionFiles = getAPKExpansionFiles(ctx, mainVersion, patchVersion);
    return getResourceZipFile(expansionFiles);
  }
}
