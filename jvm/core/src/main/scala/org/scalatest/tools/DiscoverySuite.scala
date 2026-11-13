/*
 * Copyright 2001-2013 Artima, Inc.
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
package org.scalatest.tools

import org.scalatest._
import java.util.UUID
import org.scalactic.Requirements._

/**
 * A Suite that contains as nested suites accessible suites on the runpath whose fully qualified
 * names start with the specified path. If wildcard is true, then any accessible suite whose fully
 * qualified name starts with the path will be included in nestedSuites. Else, only accessible suites that
 * are direct members of the path are included in nesteSuites.
 *
 * @author Bill Venners
 */
private[scalatest] class DiscoverySuite(
  path: String,
  accessibleSuites: Set[String],
  wildcard: Boolean,
  runpathClassLoader: ClassLoader,
  reporterOfTopLevelSuite: (Suite, Filter, Reporter) => Reporter
) extends Suite {

  def this(path: String, accessibleSuites: Set[String], wildcard: Boolean, runpathClassLoader: ClassLoader) =
    this(path, accessibleSuites, wildcard, runpathClassLoader, TestEventLogHandOff.noStructureWrapper)

  requireNonNull(path, accessibleSuites, runpathClassLoader)

  override val suiteId = getClass.getName + "-" + UUID.randomUUID.toString
    
  override val nestedSuites: collection.immutable.IndexedSeq[Suite] =
    for (suiteClassName <- DiscoverySuite.nestedSuiteNames(path, accessibleSuites, wildcard))
      yield DiscoverySuite.getSuiteInstance(suiteClassName, runpathClassLoader)

  // The suites a DiscoverySuite nests are the run's top-level suites.
  private[scalatest] override def reporterOfNestedSuite(nestedSuite: Suite, filter: Filter, reporter: Reporter): Reporter =
    reporterOfTopLevelSuite(nestedSuite, filter, reporter)

  // Runner's distributor asks for a suite's reporter as the suite starts. Any other distributor is handed the reporter.
  private[scalatest] override def distributeNestedSuite(distribute: Distributor, nestedSuite: Suite, args: Args): Status = {
    distribute match {
      case concurrent: ConcurrentDistributor => concurrent.apply(nestedSuite, args, TestEventLogHandOff.none, reporterOfTopLevelSuite)
      case other =>
        val reporter = reporterOfTopLevelSuite(nestedSuite, args.filter, args.reporter)
        val registration = SuiteRegistration.madeTaking(reporter, args.reporter)
        val status =
          try other(nestedSuite, args.copy(reporter = reporter))
          catch {
            case thrown: Throwable =>
              registration.removed()
              throw thrown
          }
        registration.removedWhenCompleted(status)
        status
    }
  }

     // TODO: probably override run to just call runNestedSuites
  override protected def runTests(testName: Option[String], args: Args): Status = {
    requireNonNull(testName, args)
    SucceededStatus
  }

}

private[scalatest] object DiscoverySuite {

  private def wildcardList(path: String, accessibleSuites: Set[String]): Set[String] = accessibleSuites.filter(_.startsWith(path))

  private def narrowList(path: String, accessibleSuites: Set[String]): Set[String] = // filter out all but ones that are direct members of the path
    for (name <- wildcardList(path, accessibleSuites); if name.length > path.length && !name.substring(path.length + 1).contains('.'))
      yield name

  private[scalatest] def nestedSuiteNames(path: String, accessibleSuites: Set[String], wildcard: Boolean): collection.immutable.IndexedSeq[String] =
    if (wildcard)
      Vector.empty ++ wildcardList(path, accessibleSuites)
    else
      Vector.empty ++ narrowList(path, accessibleSuites)

  private[scalatest] def getSuiteInstance(suiteClassName: String, loader: ClassLoader): Suite = {
    try {
      val clazz = loader.loadClass(suiteClassName)
      val wrapWithAnnotation = clazz.getAnnotation(classOf[WrapWith])
      if (wrapWithAnnotation == null)
        clazz.newInstance.asInstanceOf[Suite]
      else {
        val suiteClazz = wrapWithAnnotation.value
        val constructorList = suiteClazz.getDeclaredConstructors()
        val constructor = constructorList.find { c => 
          val types = c.getParameterTypes
          types.length == 1 && types(0) == classOf[java.lang.Class[_]]
        }
        constructor.get.newInstance(clazz).asInstanceOf[Suite]
      }
    }
    catch {
      case t: Throwable => {
        val msg = Resources.cannotLoadDiscoveredSuite(suiteClassName, t.getMessage())
        new DeferredAbortedSuite(suiteClassName, suiteClassName, new RuntimeException(msg, t))
      }
    }
  }
}


