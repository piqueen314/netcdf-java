/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.iosp.uf;

import ucar.unidata.io.RandomAccessFile;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: yuanho
 * Date: Sep 24, 2008
 * Time: 3:53:18 PM
 * To change this template use File | Settings | File Templates.
 */
public class UFheader {
  static final boolean littleEndianData = true;
  String dataFormat = "UNIVERSALFORMAT";  // temp setting
  Ray firstRay = null;
  Date endDate = null;

  Map<String, List<List<Ray>>> variableGroup;  // key = data type, value = List by sweep number
  private int max_radials = 0;
  private int min_radials = Integer.MAX_VALUE;

  public boolean isValidFile(ucar.unidata.io.RandomAccessFile raf) {
    try {
      raf.order(RandomAccessFile.BIG_ENDIAN);

      raf.seek(4);
      String ufStr = raf.readString(2);
      if (!ufStr.equals("UF"))
        return false;
      //if ufStr is UF, then a further checking apply
      raf.seek(0);
      int rsize = raf.readInt();

      byte[] buffer = new byte[rsize];
      long offset = raf.getFilePointer();
      int readBytes = raf.read(buffer, 0, rsize);
      if (readBytes != rsize) {
          return false;
      }
      int endPoint = raf.readInt();
      if (endPoint != rsize) {
        return false;
      }

      ByteBuffer bos = ByteBuffer.wrap(buffer);
      firstRay = new Ray(bos, rsize, offset);

    } catch (IOException e) {
      return (false);
    }
    return true;
  }

  void read(ucar.unidata.io.RandomAccessFile raf) throws IOException {
    Map<String, List<Ray>> rayListMap = new HashMap<>(600);  // all the rays for a variable

    raf.seek(0);
    raf.order(RandomAccessFile.BIG_ENDIAN);
    while (!raf.isAtEndOfFile()) {
      byte[] b4 = new byte[4];
      int bytesRead = raf.read(b4);
      if (bytesRead != 4) break; // done

      int rsize = bytesToInt(b4, false);
      byte[] buffer = new byte[rsize];

      long offset = raf.getFilePointer();
      raf.readFully(buffer);
      raf.readFully(b4);

      int endPoint = bytesToInt(b4, false);
      if (endPoint != rsize || rsize == 0) {
        //     System.out.println("Herr " +velocityList.size());
        continue;
      }

      ByteBuffer bos = ByteBuffer.wrap(buffer);
      Ray r = new Ray(bos, rsize, offset);
      if (firstRay == null)
      {
          firstRay = r;
          endDate = r.getDate();
      } else if (r.getTitleMsecs() > firstRay.getTitleMsecs())
        endDate = r.getDate();

      Map<String, Ray.UF_field_header2> rayMap = r.field_header_map;      // each ray has a list of variables
      for (Map.Entry<String, Ray.UF_field_header2> entry : rayMap.entrySet()) {
        String ab = entry.getKey();                                      // variable name
        List<Ray> group = rayListMap.computeIfAbsent(ab,
            k -> new ArrayList<>());                            // all the rays for this variable
        group.add(r);
      }
    }

    // now sort the rays by sweep number
    variableGroup = new HashMap<>();
    for (Map.Entry<String,List<Ray>> entry : rayListMap.entrySet()) {
      String key = entry.getKey();
      List<Ray> group = entry.getValue();
      List<List<Ray>> sortedGroup = sortScans(key, group);
      variableGroup.put(key, sortedGroup);
    }

    //System.out.println("Herr " +velocityList.size());
    //return;
  }

  private List<List<Ray>> sortScans(String name, List<Ray> rays) {

    // now group by sweepNumber
    Map<Integer, List<Ray>> sweepMap = new HashMap<>(2*rays.size());
    for ( Ray r : rays) {
      Integer groupNo = (int) r.uf_header2.sweepNumber; //.elevation);

      List<Ray> group = sweepMap.computeIfAbsent(groupNo, k -> new ArrayList<>());

      group.add(r);
    }

    // sort the groups by elevation
    List<List<Ray>> groups = new ArrayList<>(sweepMap.values());
    groups.sort(new GroupComparator());

    // count rays in each group
    for (List<Ray> group : groups) {
      max_radials = Math.max(max_radials, group.size());
      min_radials = Math.min(min_radials, group.size());
    }

    return groups;
  }

  /* public float getMeanElevation(String key, int eNum) {
    List<Ray> gp = getGroup(key);
    return getMeanElevation(gp);
  }

  public float getMeanElevation(List<Ray> gList) {
    float sum = 0;
    int size = 0;

    for (Ray r : gList) {
      sum += r.getElevation();
      size++;
    }

    return sum / size;
  }

  public List<Ray> getGroup(String key) {
    return variableGroup.get(key);
  } */

  public int getMaxRadials() {
    return max_radials;
  }

  public String getDataFormat() {
    return dataFormat;
  }

  public Date getStartDate() {
    return firstRay.getDate();
  }

  public Date getEndDate() {
    return endDate;
  }

  public float getHorizontalBeamWidth(String ab) {
    return firstRay.getHorizontalBeamWidth(ab);
  }

  public String getStationId() {
      return getSiteName();
  }

  public String getSiteName() {
    return firstRay.uf_header2.siteName;
  }

  String getRadarName() {
      return firstRay.uf_header2.radarName;
  }

  public Short getSweepMode() {
    return firstRay.uf_header2.sweepMode;
  }

  public float getStationLatitude() {
    return firstRay.getLatitude();
  }

  public float getStationLongitude() {
    return firstRay.getLongtitude();
  }

  public short getStationElevation() {
    return firstRay.uf_header2.height;
  }


  public short getMissingData() {
    return firstRay.getMissingData();
  }

  private static class GroupComparator implements Comparator<List<Ray>> {

    public int compare(List<Ray> group1, List<Ray> group2) {
      Ray ray1 = group1.get(0);
      Ray ray2 = group2.get(0);

      //if (record1.elevation_num != record2.elevation_num)
      return (ray1.uf_header2.elevation - ray2.uf_header2.elevation < 13 ? 0 : 1);
      //return record1.cut - record2.cut;
    }
  }


  protected short getShort(byte[] bytes, int offset) {
    int ndx0 = offset + (littleEndianData ? 1 : 0);
    int ndx1 = offset;
    // careful that we only allow sign extension on the highest order byte
    return (short) (bytes[ndx0] << 8 | (bytes[ndx1] & 0xff));
  }


  public static int bytesToShort(byte a, byte b, boolean swapBytes) {
    // again, high order bit is expressed left into 32-bit form
    if (swapBytes) {
      return (a & 0xff) + ((int) b << 8);
    } else {
      return ((int) a << 8) + (b & 0xff);
    }
  }

  public static int bytesToInt(byte[] bytes, boolean swapBytes) {
    byte a = bytes[0];
    byte b = bytes[1];
    byte c = bytes[2];
    byte d = bytes[3];
    if (swapBytes) {
      return ((a & 0xff)) +
              ((b & 0xff) << 8) +
              ((c & 0xff) << 16) +
              ((d & 0xff) << 24);
    } else {
      return ((a & 0xff) << 24) +
              ((b & 0xff) << 16) +
              ((c & 0xff) << 8) +
              ((d & 0xff));
    }
  }


}
