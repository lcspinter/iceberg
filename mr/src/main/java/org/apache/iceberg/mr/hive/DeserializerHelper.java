/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.mr.hive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.MapObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StandardStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.BinaryObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.BooleanObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DoubleObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.FloatObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.HiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.IntObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.LongObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.StringObjectInspector;
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.mr.hive.serde.objectinspector.IcebergObjectInspector;
import org.apache.iceberg.mr.hive.serde.objectinspector.IcebergReadObjectInspector;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

class DeserializerHelper {

  private DeserializerHelper() {
  }

  static Record deserialize(Object data, Schema tableSchema, ObjectInspector objectInspector) throws SerDeException {
    Preconditions.checkArgument(objectInspector.getCategory() == ObjectInspector.Category.STRUCT);

    StructObjectInspector soi = (StructObjectInspector) objectInspector;
    List<Object> writableObj = soi.getStructFieldsDataAsList(data);
    List<? extends StructField> fields = soi.getAllStructFieldRefs();

    Record record = GenericRecord.create(tableSchema);
    for (int i = 0; i < tableSchema.columns().size(); i++) {
      StructField field = fields.get(i);
      Object value = writableObj.get(i);

      if (value == null) {
        record.setField(tableSchema.findColumnName(i), null);
      } else {
        Type type = tableSchema.columns().get(i).type();
        ObjectInspector fieldInspector = field.getFieldObjectInspector();
        record.set(i, getRecordValue(type, fieldInspector, value));
      }
    }
    return record;
  }

  private static Object getRecordValue(Type type, ObjectInspector fieldInspector, Object value) throws SerDeException {
    switch (type.typeId()) {
      case BOOLEAN:
        boolean boolVal = ((BooleanObjectInspector) fieldInspector).get(value);
        return boolVal;
      case INTEGER:
        int intVal = ((IntObjectInspector) fieldInspector).get(value);
        return intVal;
      case LONG:
        long longVal = ((LongObjectInspector) fieldInspector).get(value);
        return longVal;
      case FLOAT:
        float floatVal = ((FloatObjectInspector) fieldInspector).get(value);
        return floatVal;
      case DOUBLE:
        double doubleVal = ((DoubleObjectInspector) fieldInspector).get(value);
        return doubleVal;
      case DATE:
        Object dateVal = ((IcebergReadObjectInspector) IcebergObjectInspector.DATE_INSPECTOR).getIcebergObject(value);
        return dateVal;
      case TIMESTAMP:
        // TODO: handle timezone in Hive 3.x where Hive type also has TZ
        Types.TimestampType timestampType = (Types.TimestampType) type;
        ObjectInspector readObjectInspector = timestampType.shouldAdjustToUTC() ?
                IcebergObjectInspector.TIMESTAMP_INSPECTOR_WITH_TZ : IcebergObjectInspector.TIMESTAMP_INSPECTOR;
        return ((IcebergReadObjectInspector) readObjectInspector).getIcebergObject(value);
      case STRING:
        String stringVal = ((StringObjectInspector) fieldInspector).getPrimitiveJavaObject(value);
        return stringVal;
      case UUID:
        String stringUuidVal = ((StringObjectInspector) fieldInspector).getPrimitiveJavaObject(value);
        // TODO: This will not work with Parquet. Parquet UUID expect byte[], others are expecting UUID
        return stringUuidVal;
      case FIXED:
        byte[] bytesVal = ((BinaryObjectInspector) fieldInspector).getPrimitiveJavaObject(value);
        return bytesVal;
      case BINARY:
        byte[] binaryBytesVal = ((BinaryObjectInspector) fieldInspector).getPrimitiveJavaObject(value);
        return binaryBytesVal;
      case DECIMAL:
        BigDecimal decimalVal =
                ((HiveDecimalObjectInspector) fieldInspector).getPrimitiveJavaObject(value).bigDecimalValue();
        return decimalVal;
      case STRUCT:
        Record recordVal = GenericRecord.create(type.asStructType());
        StandardStructObjectInspector structObjectInspector = (StandardStructObjectInspector) fieldInspector;
        for (StructField structFieldRef : structObjectInspector.getAllStructFieldRefs()) {
          Object structFieldData = structObjectInspector.getStructFieldData(value, structFieldRef);
          recordVal.setField(structFieldRef.getFieldName(),
                  getRecordValue(type.asStructType().field(structFieldRef.getFieldName()).type(),
                          structFieldRef.getFieldObjectInspector(), structFieldData));
        }
        return recordVal;
      case LIST:
        List<Object> listVal = new ArrayList<>();
        ListObjectInspector listObjectInspector = (ListObjectInspector) fieldInspector;
        ObjectInspector listElementObjectInspector = listObjectInspector.getListElementObjectInspector();
        for (Object val : listObjectInspector.getList(value)) {
          listVal.add(getRecordValue(type.asListType().elementType(), listElementObjectInspector, val));
        }
        return listVal;
      case MAP:
        Map<Object, Object> mapVal = new HashMap<>();
        MapObjectInspector mapObjectInspector = (MapObjectInspector) fieldInspector;
        ObjectInspector mapKeyObjectInspector = mapObjectInspector.getMapKeyObjectInspector();
        ObjectInspector mapValueObjectInspector = mapObjectInspector.getMapValueObjectInspector();
        Types.MapType mapType = type.asMapType();
        for (Map.Entry<?, ?> entry : mapObjectInspector.getMap(value).entrySet()) {
          mapVal.put(getRecordValue(mapType.keyType(), mapKeyObjectInspector, entry.getKey()),
                  getRecordValue(mapType.valueType(), mapValueObjectInspector, entry.getValue()));
        }
        return mapVal;
      case TIME:
      default:
        throw new SerDeException("Unsupported column type: " + type);
    }
  }
}
