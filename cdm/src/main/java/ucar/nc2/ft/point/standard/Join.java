/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.ft.point.standard;

import ucar.ma2.*;
import ucar.nc2.Variable;
import ucar.nc2.dataset.VariableDS;

/**
 * An abstract way to 'join' more cols to a row
 */
public interface Join {

  /**
   * Get 'join' data to be added to the row.
   * @param cursor the state of the iteration
   * @return extra data to be added to the row
   */
  StructureData getJoinData(Cursor cursor);

  /**
   * Find the Variable of the given name in the joined table.
   * @param varName find this Variable
   * @return the named Variable, or null
   */
  VariableDS findVariable(String varName);

  Variable getExtraVariable();

}

