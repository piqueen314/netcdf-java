/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.iosp;

import java.nio.*;

/**
 * A Layout that supplies the "source" ByteBuffer.
 * This is used when the data must be massaged after being read, eg uncompresed or filtered.
 * The modified data is placed in a ByteBuffer, which may change for different chunks, and
 * so is supplied by each chunk.
 * 
 * <p/>
 * Example for Integers:
 * <pre>

  int[] read( LayoutBB index, int[] pa) {
      while (index.hasNext()) {
        LayoutBB.Chunk chunk = index.next();
        IntBuffer buff = chunk.getIntBuffer();
        buff.position(chunk.getSrcElem());
        int pos = (int) chunk.getDestElem();
        for (int i = 0; i < chunk.getNelems(); i++)
          pa[pos++] = buff.get();
      }
      return pa;
  }

 * </pre>
 *
 * @author caron
 * @since Jan 9, 2008
 */

public interface LayoutBB extends Layout {

  /**
   * Get total number of elements in the wanted subset.
   *
   * @return total number of elements in the wanted subset.
   */
  long getTotalNelems();

  /**
   * Get size of each element in bytes.
   *
   * @return size of each element in bytes.
   */
  int getElemSize();

  /**
   * Is there more to do
   *
   * @return true if theres more to do
   */
  boolean hasNext();

  /**
   * Get the next chunk
   *
   * @return next chunk, or null if !hasNext()
   */
  Chunk next();

  /**
   * A chunk of data that is contiguous in both the source and destination.
   * Read nelems from ByteBuffer at filePos, store in destination at startElem.
   */
  interface Chunk extends Layout.Chunk {

    /**
     * Get the position in source <Type>Buffer where to read or write: "file position"
     * @return position as a element index into the <Type>Buffer
     */
    int getSrcElem();

    ByteBuffer getByteBuffer();
    ShortBuffer getShortBuffer();
    IntBuffer getIntBuffer();
    FloatBuffer getFloatBuffer();
    DoubleBuffer getDoubleBuffer();
    LongBuffer getLongBuffer();

    /**
     * Get number of elements to transfer contiguously (Note: elements, not bytes)
     *
     * @return number of elements to transfer
     */
    int getNelems();

    /**
     * Get starting element position as a 1D element index into the destination, eg the requested array with shape "wantSection".
     *
     * @return starting element in the array (Note: elements, not bytes)
     */
    long getDestElem();
  }
}
