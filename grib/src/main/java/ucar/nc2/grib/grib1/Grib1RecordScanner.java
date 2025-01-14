/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.grib.grib1;

import javax.annotation.Nullable;
import ucar.nc2.grib.GribNumbers;
import ucar.unidata.io.KMPMatch;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.util.StringUtil2;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Scan files and extract Grib1Records. usage:
 * <pre>
 * Grib1RecordScanner reader = new Grib1RecordScanner(raf);
 * while (reader.hasNext()) {
 * ucar.nc2.grib.grib1.Grib1Record gr = reader.next();
 * Grib1SectionProductDefinition pds = gr.getPDSsection();
 * Grib1SectionGridDefinition gds = gr.getGDSsection();
 * ...
 * }
 *
 * </pre>
 *
 * @author John
 * @since 9/3/11
 */
public class Grib1RecordScanner {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Grib1RecordScanner.class);
  private static final KMPMatch matcher = new KMPMatch(new byte[]{'G', 'R', 'I', 'B'});
  private static final boolean debug = false;
  private static final boolean debugGds = false;
  private static final int maxScan = 16000;

  static boolean allowBadIsLength = false;
  static boolean allowBadDsLength = false; // ECMWF workaround

  public static void setAllowBadIsLength(boolean allowBadIsLength) {
    Grib1RecordScanner.allowBadIsLength = allowBadIsLength;
  }

  public static void setAllowBadDsLength(boolean allowBadDsLength) {
    Grib1RecordScanner.allowBadDsLength = allowBadDsLength;
  }

  public static boolean isValidFile(RandomAccessFile raf) {
    try {
      raf.seek(0);
      boolean found = raf.searchForward(matcher, maxScan); // look in first 16K
      if (!found) {
        return false;
      }
      raf.skipBytes(4); // will be positioned on byte 0 of indicator section
      int len = GribNumbers.uint3(raf);
      int edition = raf.read(); // read at byte 8
      if (edition != 1) {
        return false;
      }

      /* Due to a trick done by ECMWF's GRIBEX to support large GRIBs, we need a special treatment
       * to fix the length of the GRIB message. See:
       * https://software.ecmwf.int/wiki/display/EMOS/Changes+in+cycle+000281
       * https://github.com/Unidata/thredds/issues/445
       */
      len = getFixedTotalLengthEcmwfLargeGrib(raf, len);

      // check ending = 7777
      if (len > raf.length()) {
        return false;
      }
      if (allowBadIsLength) {
        return true;
      }

      raf.skipBytes(len - 12);
      for (int i = 0; i < 4; i++) {
        if (raf.read() != 55) {
          return false;
        }
      }
      return true;

    } catch (IOException e) {
      return false;
    }
  }

  static int getFixedTotalLengthEcmwfLargeGrib(RandomAccessFile raf, int len) throws IOException {
    int lenActual = len;
    if ((len & 0x800000) == 0x800000) {
      long pos0 = raf.getFilePointer(); // remember the actual pos
      int lenS1 = GribNumbers.uint3(raf); // section1Length
      raf.skipBytes(1); // table2Version
      if (GribNumbers.uint(raf) == 98) { // center (if ECMWF make the black magic)
        raf.skipBytes(2); // generatingProcessIdentifier, gridDefinition
        int s1f = GribNumbers.uint(raf); // section1Flags
        raf.skipBytes(lenS1 - (3 + 5)); // skips to next section
        int lenS2;
        int lenS3;
        if ((s1f & 128) == 128) { // section2 GDS exists
          lenS2 = GribNumbers.uint3(raf); // section2Length
          raf.skipBytes(lenS2 - 3); // skips to next section
        }
        if ((s1f & 64) == 64) { // section3 BMS exists
          lenS3 = GribNumbers.uint3(raf); // section3Length
          raf.skipBytes(lenS3 - 3); // skips to next section
        }
        int lenS4 = GribNumbers.uint3(raf); // section4Length
        if (lenS4 < 120) { // here we are!!!!
          lenActual = (len & 0x7FFFFF) * 120 - lenS4 + 4; // the actual totalLength
        }
      }
      raf.seek(pos0); // recall the pos
    }
    return lenActual;
  }

////////////////////////////////////////////////////////////

  private final Map<Long, Grib1SectionGridDefinition> gdsMap = new HashMap<>();
  private final ucar.unidata.io.RandomAccessFile raf;

  private byte[] header;
  private long lastPos;

  public Grib1RecordScanner(RandomAccessFile raf) throws IOException {
    this.raf = raf;
    raf.seek(0);
    raf.order(RandomAccessFile.BIG_ENDIAN);
    lastPos = 0;
  }

  public boolean hasNext() throws IOException {
    if (lastPos >= raf.length()) {
      return false;
    }
    boolean more;
    long foundAt = 0;

    while (true) { // scan until we get a GRIB-1 or more is false
      raf.seek(lastPos);
      more = raf.searchForward(matcher, -1); // will scan to end for a 'GRIB' string
      if (!more) {
        break;
      }

      foundAt = raf.getFilePointer();
      // see if its GRIB-1
      raf.skipBytes(7);
      int edition = raf.read();
      if (edition == 1) {
        break;
      }
      lastPos = raf.getFilePointer(); // not edition 1 ! could terminate ??
    }

    if (more) {
      // read the header - stuff between the records
      int sizeHeader = (int) (foundAt - lastPos);
      if (sizeHeader > 100) {
        sizeHeader = 100;   // maximum 100 bytes, more likely to be garbage
      }
      long startPos = foundAt - sizeHeader;
      header = new byte[sizeHeader];
      raf.seek(startPos);
      raf.readFully(header);
      raf.seek(foundAt);
      this.lastPos = foundAt; // ok start from here next time
    }

    return more;
  }

  @Nullable
  public Grib1Record next() throws IOException {

    Grib1SectionIndicator is = null;
    try {
      is = new Grib1SectionIndicator(raf);
      Grib1SectionProductDefinition pds = new Grib1SectionProductDefinition(raf);
      Grib1SectionGridDefinition gds = pds.gdsExists() ? new Grib1SectionGridDefinition(raf)
          : new Grib1SectionGridDefinition(pds);
      if (!pds.gdsExists() && debugGds) {
        log.warn(" NO GDS: center = %d, GridDefinition=%d file=%s%n", pds.getCenter(),
            pds.getGridDefinition(), raf.getLocation());
      }

      Grib1SectionBitMap bitmap = pds.bmsExists() ? new Grib1SectionBitMap(raf) : null;
      Grib1SectionBinaryData dataSection = new Grib1SectionBinaryData(raf);

      long ending = is.getEndPos();
      long dataEnding = dataSection.getStartingPosition() + dataSection.getLength();

      if (dataEnding > is.getEndPos()) { // presumably corrupt
        // raf.seek(dataSection.getStartingPosition()); // go back to start of the dataSection, in hopes of salvaging
        log.warn("BAD GRIB-1 data message at " + dataSection.getStartingPosition() + " header= "
            + StringUtil2.cleanup(header) + " for=" + raf.getLocation());
        throw new IllegalStateException("Illegal Grib1SectionBinaryData Message Length");
      }

      /* ecmwf offset by 1 bug - LOOK not sure if this is still needed
          // obtain BMS or BDS offset in the file for this product
          if (pds.getPdsVars().getCenter() == 98) {  // check for ecmwf offset by 1 bug
            int length = GribNumbers.uint3(raf);  // should be length of BMS
            if ((length + raf.getFilePointer()) < EOR) {
              dataOffset = raf.getFilePointer() - 3;  // ok
            } else {
              //System.out.println("ECMWF off by 1 bug" );
              dataOffset = raf.getFilePointer() - 2;
            }
          } else {
            dataOffset = raf.getFilePointer();
          }
      */

      // look for duplicate gds
      long crc = gds.calcCRC();
      Grib1SectionGridDefinition gdsCached = gdsMap.get(crc);
      if (gdsCached != null) {
        gds = gdsCached;
      } else {
        gdsMap.put(crc, gds);
      }

      // check that end section is correct
      boolean foundEnding = checkEnding(ending);
      log.debug(" read until %d grib ending at %d header ='%s' foundEnding=%s%n",
            raf.getFilePointer(), ending, StringUtil2.cleanup(header), foundEnding);

      if (!foundEnding && (allowBadIsLength || is.isMessageLengthFixed)) {
        foundEnding = checkEnding(dataSection.getStartingPosition() + dataSection.getLength());
      }

      if (!foundEnding && (allowBadDsLength || is.isMessageLengthFixed)) {
        foundEnding = true;
      }

      if (foundEnding) {
        lastPos = raf.getFilePointer();
        return new Grib1Record(header, is, gds, pds, bitmap, dataSection);
      }

      // skip this record
      // lastPos = is.getEndPos() + 20;  cant use is.getEndPos(), may be bad
      lastPos += 20;  // skip over the "GRIB" of this message
      if (hasNext()) { // search forward for another one
        return next();
      }

    } catch (Throwable t) {
      long pos = (is == null) ? -1 : is.getStartPos();
      log.warn("Bad Grib1 record in file {}, skipping pos={}", raf.getLocation(), pos);
      // t.printStackTrace();
      lastPos += 20; // skip over the "GRIB"
      if (hasNext()) { // search forward for another one
        return next();
      }
    }

    // EOF that hasNext() couldnt detect
    return null;
  }

  private boolean checkEnding(long ending) throws IOException {
    // check that end section = "7777" is correct
    raf.seek(ending - 4);
    for (int i = 0; i < 4; i++) {
      if (raf.read() != 55) {
        String clean = StringUtil2.cleanup(header);
        if (clean.length() > 40) {
          clean = clean.substring(0, 40) + "...";
        }
        log.debug(
            "Missing End of GRIB message at pos=" + ending + " header= " + clean + " for=" + raf
                .getLocation());
        return false;
      }
    }
    return true;
  }

  // Count the number of records in a grib1 file.
  public static void main(String[] args) throws IOException {
    int count = 0;
    String file = (args.length > 0) ? args[0] : "Q:/cdmUnitTest/formats/grib1/ECMWF.hybrid.grib1";
    RandomAccessFile raf = new RandomAccessFile(file, "r");
    System.out.printf("Read %s%n", raf.getLocation());
    Grib1RecordScanner scan = new Grib1RecordScanner(raf);
    while (scan.hasNext()) {
      scan.next();
      count++;
    }
    raf.close();
    System.out.printf("count=%d%n", count);
  }
}
