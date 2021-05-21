/*
 * Copyright (2020) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.delta.storage

import com.google.common.base.Throwables
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileAlreadyExistsException

import org.apache.spark.sql.delta.storage.HadoopFileSystemLogStore
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import org.apache.spark.SparkConf
import org.apache.spark.annotation.Unstable

/**
 * :: Unstable ::
 *
 * LogStore implementation for IBM Cloud Object Storage.
 *
 * We assume the following from COS's [[FileSystem]] implementations:
 * - Write on COS is all-or-nothing, whether overwrite or not.
 * - Write is atomic.
 *   Note: Write is atomic when using the Stocator v1.1.1+ - Storage Connector for Apache Spark
 *   (https://github.com/CODAIT/stocator) by setting the configuration `fs.cos.atomic.write` to true
 *   (for more info see the documentation for Stocator)
 * - List-after-write is consistent.
 *
 * @note This class is not meant for direct access but for configuration based on storage system.
 *       See https://docs.delta.io/latest/delta-storage.html for details.
 */
@Unstable
class IBMCOSLogStore(sparkConf: SparkConf, hadoopConf: Configuration)
  extends HadoopFileSystemLogStore(sparkConf, hadoopConf) {
  val preconditionFailedExceptionMessage =
    "At least one of the preconditions you specified did not hold"

  assert(hadoopConf.getBoolean("fs.cos.atomic.write", false) == true,
    "'fs.cos.atomic.write' must be set to true to use IBMCOSLogStore " +
      "in order to enable atomic write")

  override def write(path: Path, actions: Iterator[String], overwrite: Boolean = false): Unit = {
    val fs = path.getFileSystem(hadoopConf)

    val exists = fs.exists(path)
    if (exists && overwrite == false) {
      throw new FileAlreadyExistsException(path.toString)
    } else {
      // create is atomic
      val stream = fs.create(path, overwrite)
      try {
        var writeSize = 0L
        actions.map(_ + "\n").map(_.getBytes(UTF_8)).foreach(action => {
          stream.write(action)
          writeSize += action.length
        })
        stream.close()
      } catch {
        case e: IOException if isPreconditionFailure(e) =>
          if (fs.exists(path)) {
            throw new FileAlreadyExistsException(path.toString)
          } else {
            throw new IllegalStateException(s"Failed due to concurrent write", e)
          }
      }
    }
  }

  private def isPreconditionFailure(x: Throwable): Boolean = {
    Throwables.getCausalChain(x)
      .stream()
      .filter(p => p != null)
      .filter(p => p.getMessage != null)
      .filter(p => p.getMessage.contains(preconditionFailedExceptionMessage))
      .findFirst
      .isPresent;
  }

  override def invalidateCache(): Unit = {}

  override def isPartialWriteVisible(path: Path): Boolean = false
}
