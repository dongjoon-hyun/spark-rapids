/*
 * Copyright (c) 2020-2023, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import ai.rapids.cudf.{ContiguousTable, DeviceMemoryBuffer, HostMemoryBuffer}
import com.nvidia.spark.rapids.Arm.{closeOnExcept, withResource}

import org.apache.spark.TaskContext
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * Holds a ColumnarBatch that the backing buffers on it can be spilled.
 */
trait SpillableColumnarBatch extends AutoCloseable {
  /**
   * The number of rows stored in this batch.
   */
  def numRows(): Int

  /**
   * Set a new spill priority.
   */
  def setSpillPriority(priority: Long): Unit

  /**
   * Get the columnar batch.
   * @note It is the responsibility of the caller to close the batch.
   * @note If the buffer is compressed data then the resulting batch will be built using
   *       `GpuCompressedColumnVector`, and it is the responsibility of the caller to deal
   *       with decompressing the data if necessary.
   */
  def getColumnarBatch(): ColumnarBatch

  def sizeInBytes: Long

  def dataTypes: Array[DataType]
}

/**
 * Cudf does not support a table with columns and no rows. This takes care of making one of those
 * spillable, even though in reality there is no backing buffer.  It does this by just keeping the
 * row count in memory, and not dealing with the catalog at all.
 */
class JustRowsColumnarBatch(numRows: Int)
    extends SpillableColumnarBatch {
  override def numRows(): Int = numRows
  override def setSpillPriority(priority: Long): Unit = () // NOOP nothing to spill

  def getColumnarBatch(): ColumnarBatch = {
    GpuSemaphore.acquireIfNecessary(TaskContext.get())
    new ColumnarBatch(Array.empty, numRows)
  }

  override def close(): Unit = () // NOOP nothing to close
  override val sizeInBytes: Long = 0L

  override def dataTypes: Array[DataType] = Array.empty
}

/**
 * The implementation of [[SpillableColumnarBatch]] that points to buffers that can be spilled.
 * @note the buffer should be in the cache by the time this is created and this is taking over
 *       ownership of the life cycle of the batch.  So don't call this constructor directly please
 *       use `SpillableColumnarBatch.apply` instead.
 */
class SpillableColumnarBatchImpl (
    handle: RapidsBufferHandle,
    rowCount: Int,
    sparkTypes: Array[DataType])
    extends SpillableColumnarBatch {

  override def dataTypes: Array[DataType] = sparkTypes
  /**
   * The number of rows stored in this batch.
   */
  override def numRows(): Int = rowCount

  private def withRapidsBuffer[T](fn: RapidsBuffer => T): T = {
    withResource(RapidsBufferCatalog.acquireBuffer(handle)) { rapidsBuffer =>
      fn(rapidsBuffer)
    }
  }

  override lazy val sizeInBytes: Long =
    withRapidsBuffer(_.getMemoryUsedBytes)

  /**
   * Set a new spill priority.
   */
  override def setSpillPriority(priority: Long): Unit = {
    handle.setSpillPriority(priority)
  }

  override def getColumnarBatch(): ColumnarBatch = {
    withRapidsBuffer { rapidsBuffer =>
      GpuSemaphore.acquireIfNecessary(TaskContext.get())
      rapidsBuffer.getColumnarBatch(sparkTypes)
    }
  }

  /**
   * Remove the `ColumnarBatch` from the cache.
   */
  override def close(): Unit = {
    // closing my reference
    handle.close()
  }
}

object SpillableColumnarBatch {
  /**
   * Create a new SpillableColumnarBatch.
   *
   * @note This takes over ownership of batch, and batch should not be used after this.
   * @param batch         the batch to make spillable
   * @param priority      the initial spill priority of this batch
   */
  def apply(batch: ColumnarBatch,
      priority: Long): SpillableColumnarBatch = {
    val numRows = batch.numRows()
    if (batch.numCols() <= 0) {
      // We consumed it
      batch.close()
      new JustRowsColumnarBatch(numRows)
    } else {
      val types = GpuColumnVector.extractTypes(batch)
      val handle = addBatch(batch, priority)
      new SpillableColumnarBatchImpl(
        handle,
        numRows,
        types)
    }
  }

  /**
   * Create a new SpillableColumnarBatch
   * @note This takes over ownership of `ct`, and `ct` should not be used after this.
   * @param ct contiguous table containing the batch GPU data
   * @param sparkTypes array of Spark types describing the data schema
   * @param priority the initial spill priority of this batch
   */
  def apply(
      ct: ContiguousTable,
      sparkTypes: Array[DataType],
      priority: Long): SpillableColumnarBatch = {
    withResource(ct) { _ =>
      val handle = RapidsBufferCatalog.addContiguousTable(ct, priority)
      new SpillableColumnarBatchImpl(handle, ct.getRowCount.toInt, sparkTypes)
    }
  }

  private[this] def allFromSameBuffer(batch: ColumnarBatch): Boolean = {
    var bufferAddr = 0L
    var isSet = false
    val numColumns = batch.numCols()
    (0 until numColumns).forall { i =>
      batch.column(i) match {
        case fb: GpuColumnVectorFromBuffer =>
          if (!isSet) {
            bufferAddr = fb.getBuffer.getAddress
            isSet = true
            true
          } else {
            bufferAddr == fb.getBuffer.getAddress
          }
        case _ => false
      }
    }
  }

  private[this] def addBatch(
      batch: ColumnarBatch,
      initialSpillPriority: Long): RapidsBufferHandle = {
    withResource(batch) { batch =>
      val numColumns = batch.numCols()
      if (GpuCompressedColumnVector.isBatchCompressed(batch)) {
        val cv = batch.column(0).asInstanceOf[GpuCompressedColumnVector]
        val buff = cv.getTableBuffer
        RapidsBufferCatalog.addBuffer(buff, cv.getTableMeta, initialSpillPriority)
      } else if (GpuPackedTableColumn.isBatchPacked(batch)) {
        val cv = batch.column(0).asInstanceOf[GpuPackedTableColumn]
        RapidsBufferCatalog.addContiguousTable(
          cv.getContiguousTable,
          initialSpillPriority)
      } else if (numColumns > 0 &&
          allFromSameBuffer(batch)) {
        val cv = batch.column(0).asInstanceOf[GpuColumnVectorFromBuffer]
        val buff = cv.getBuffer
        RapidsBufferCatalog.addBuffer(buff, cv.getTableMeta, initialSpillPriority)
      } else {
        RapidsBufferCatalog.addBatch(batch, initialSpillPriority)
      }
    }
  }

}

/**
 * Just like a SpillableColumnarBatch but for buffers.
 */
class SpillableBuffer(
    handle: RapidsBufferHandle) extends AutoCloseable {

  /**
   * Set a new spill priority.
   */
  def setSpillPriority(priority: Long): Unit = {
    handle.setSpillPriority(priority)
  }

  /**
   * Use the device buffer.
   */
  def getDeviceBuffer(): DeviceMemoryBuffer = {
    withResource(RapidsBufferCatalog.acquireBuffer(handle)) { rapidsBuffer =>
      rapidsBuffer.getDeviceMemoryBuffer
    }
  }

  /**
   * Remove the buffer from the cache.
   */
  override def close(): Unit = {
    handle.close()
  }
}

/**
 * This represents a spillable `HostMemoryBuffer` and adds an interface to access
 * this host buffer at the host layer, unlike `SpillableBuffer` (device)
 * @param handle an object used to refer to this buffer in the spill framework
 * @param length a metadata-only length that is kept in the `SpillableHostBuffer`
 *               instance. Used in cases where the backing host buffer is larger
 *               than the number of usable bytes.
 * @param catalog this was added for tests, it defaults to
 *                `RapidsBufferCatalog.singleton` in the companion object.
 */
class SpillableHostBuffer(handle: RapidsBufferHandle,
                          val length: Long,
                          catalog: RapidsBufferCatalog) extends AutoCloseable {
  /**
   * Set a new spill priority.
   */
  def setSpillPriority(priority: Long): Unit = {
    handle.setSpillPriority(priority)
  }

  /**
   * Remove the buffer from the cache.
   */
  override def close(): Unit = {
    handle.close()
  }

  /**
   * Acquires the underlying `RapidsBuffer` and uses
   * `RapidsBuffer.withMemoryBufferReadLock` to obtain a read lock
   * that will held while invoking `body` with a `HostMemoryBuffer`.
   * @param body function that takes a `HostMemoryBuffer` and produces `K`
   * @tparam K any return type specified by `body`
   * @return the result of body(hostMemoryBuffer)
   */
  def withHostBufferReadOnly[K](body: HostMemoryBuffer => K): K = {
    withResource(catalog.acquireBuffer(handle)) { rapidsBuffer =>
      rapidsBuffer.withMemoryBufferReadLock {
        case hmb: HostMemoryBuffer => body(hmb)
        case memoryBuffer =>
          throw new IllegalStateException(
            s"Expected a HostMemoryBuffer but instead got ${memoryBuffer}")
      }
    }
  }

  /**
   * Acquires the underlying `RapidsBuffer` and uses
   * `RapidsBuffer.withMemoryBufferWriteLock` to obtain a write lock
   * that will held while invoking `body` with a `HostMemoryBuffer`.
   * @param body function that takes a `HostMemoryBuffer` and produces `K`
   * @tparam K any return type specified by `body`
   * @return the result of body(hostMemoryBuffer)
   */
  def withHostBufferWriteLock[K](body: HostMemoryBuffer => K): K = {
    withResource(catalog.acquireBuffer(handle)) { rapidsBuffer =>
      rapidsBuffer.withMemoryBufferWriteLock {
        case hmb: HostMemoryBuffer => body(hmb)
        case memoryBuffer =>
          throw new IllegalStateException(
            s"Expected a HostMemoryBuffer but instead got ${memoryBuffer}")
      }
    }
  }
}

object SpillableBuffer {

  /**
   * Create a new SpillableBuffer.
   * @note This takes over ownership of buffer, and buffer should not be used after this.
   * @param buffer the buffer to make spillable
   * @param priority the initial spill priority of this buffer
   */
  def apply(
      buffer: DeviceMemoryBuffer,
      priority: Long): SpillableBuffer = {
    val meta = MetaUtils.getTableMetaNoTable(buffer.getLength)
    val handle = withResource(buffer) { _ => 
      RapidsBufferCatalog.addBuffer(buffer, meta, priority)
    }
    new SpillableBuffer(handle)
  }
}

object SpillableHostBuffer {

  /**
   * Create a new SpillableBuffer.
   * @note This takes over ownership of buffer, and buffer should not be used after this.
   * @param length the actual length of the data within the host buffer, which
   *               must be <= than buffer.getLength, otherwise this function throws
   *               and closes `buffer`
   * @param buffer the buffer to make spillable
   * @param priority the initial spill priority of this buffer
   */
  def apply(buffer: HostMemoryBuffer,
            length: Long,
            priority: Long,
            catalog: RapidsBufferCatalog = RapidsBufferCatalog.singleton): SpillableHostBuffer = {
    closeOnExcept(buffer) { _ =>
      require(length <= buffer.getLength,
        s"Attempted to add a host spillable with a length ${length} B which is " +
          s"greater than the backing host buffer length ${buffer.getLength} B")
    }
    val meta = MetaUtils.getTableMetaNoTable(buffer.getLength)
    val handle = withResource(buffer) { _ =>
      catalog.addBuffer(buffer, meta, priority)
    }
    new SpillableHostBuffer(handle, length, catalog)
  }
}