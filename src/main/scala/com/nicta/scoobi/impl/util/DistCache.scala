/**
 * Copyright 2011,2012 National ICT Australia Limited
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
package com.nicta.scoobi
package impl
package util

import org.apache.hadoop.fs._
import org.apache.hadoop.filecache._
import org.apache.hadoop.conf.Configuration
import Configurations._
import ScoobiConfiguration._
import java.net.URI
import DistributedCache._
import java.io.IOException
import org.apache.commons.logging.LogFactory
import control.Exceptions._

/**
 * Facilitate making an object available to all tasks (mappers, reducers, etc). Use
 * XStream to serialise objects to XML strings and then send out via Hadoop's
 * distributed cache. Two APIs are provided for pushing and pulling objects.
 */
object DistCache {
  private lazy val logger = LogFactory.getLog("scoobi.DistCache")

  /**
   * Make a local filesystem path based on a 'tag' to temporarily store the
   * serialised object.
   */
  def tagToPath(configuration: Configuration, tag: String): Path = {
    val sc = ScoobiConfigurationImpl(configuration)
    new Path(s"${sc.workingDirectory}/dist-objs/$tag")
  }

  /**
   * Distribute an object to be available for tasks in the current job
   *
   * By default check right away if the object can be deserialised
   */
  def pushObject[T](configuration: Configuration, obj: T, tag: String, check: Boolean = true): Path = {
    val path = serialise[T](configuration, obj, tag) { path =>
      DistributedCache.addCacheFile(path.toUri, configuration)
    }

    if (check)
      try Serialiser.deserialise(path.getFileSystem(configuration).open(path))
      catch { case e: Throwable => throw new IOException(s"The object $obj can not be serialised/deserialised: ${e.getMessage}", e) }

    path
  }

  /**
   * serialise an object to a path
   */
  private def serialise[T](configuration: Configuration, obj: T, tag: String)(action: Path => Unit): Path = {
    /* Serialise */
    val path = tagToPath(configuration, tag)
    val dos = path.getFileSystem(configuration).create(path)
    Serialiser.serialise(obj, dos)
    action(path)
    path
  }

  /** Get an object that has been distributed so as to be available for tasks in
    * the current job. */
  def pullObject[T](configuration: Configuration, tag: String): Option[T] =
    pullPath(configuration, tagToPath(configuration, tag)) { dis =>
      Serialiser.deserialise(dis).asInstanceOf[T]
    }

  /**
   * Pulling an object from a given path.
   *
   * We first try paths from:
   *
   *  - the local cache then
   *  - the distributed cache then
   *  - the passed path itself (this is useful if we are on the client)
   *
   * Once a FSDataInputStream is successfully opened, the function f can do its job to recreate the object:
   *
   *  - use the Serialiser to deserialise the object
   *  - use a WireFormat to deserialise the object
   */
  def pullPath[T](configuration: Configuration, path: Path)(f: FSDataInputStream => T): Option[T] = {
    lazy val cacheFiles = (Option(DistributedCache.getLocalCacheFiles(configuration)).getOrElse(Array[Path]()).map(p => new Path("file://"+p.toString)) ++
                           Option(DistributedCache.getCacheFiles(configuration)).getOrElse(Array[URI]()).map(new Path(_))).toStream :+ path

    logger.info("trying to pull an object from the cache at path: "+path)
    cacheFiles.filter(p => p.toString.endsWith(path.getName)).map { case p =>
      logger.info("trying to open: "+p)
      tryo(p.getFileSystem(configuration).open(p)).map { dis =>
        logger.info("successfully opened: "+p)
        try f(dis)
        finally dis.close
      }
    }.dropWhile(!_.isDefined).flatten.headOption match {
      case Some(o) => Some(o)
      case None    => logger.error(cacheFiles.mkString("No successfully opened path. The cache files which were used are\n", "\n", "\n")); None
    }
  }

}

