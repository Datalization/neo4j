/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_2.commands

import org.neo4j.cypher.internal.compiler.v3_2.ExecutionContext
import org.neo4j.cypher.internal.compiler.v3_2.commands.expressions.{Expression, InequalitySeekRangeExpression, PrefixSeekRangeExpression}
import org.neo4j.cypher.internal.compiler.v3_2.helpers.IsList
import org.neo4j.cypher.internal.compiler.v3_2.mutation.{GraphElementPropertyFunctions, makeValueNeoSafe}
import org.neo4j.cypher.internal.compiler.v3_2.pipes.QueryState
import org.neo4j.cypher.internal.frontend.v3_2.CypherTypeException
import org.neo4j.graphdb.Node

import scala.collection.GenTraversableOnce

object indexQuery extends GraphElementPropertyFunctions {
  def apply(queryExpression: QueryExpression[Expression],
            m: ExecutionContext,
            state: QueryState,
            index: Any => GenTraversableOnce[Node],
            labelName: String,
            propertyNames: Seq[String]): Iterator[Node] = queryExpression match {

    case SingleQueryExpression(inner) =>
      val value = inner(m)(state)
      lookupNodes(value, index).toIterator

    case ManyQueryExpression(inner) =>
      inner(m)(state) match {
        case IsList(coll) => coll.toSet.toIndexedSeq.flatMap {
          value: Any => lookupNodes(value, index)
        }.iterator
        case null => Iterator.empty
        case _ => throw new CypherTypeException(s"Expected the value for looking up :$labelName(${propertyNames.mkString(",")}) to be a collection but it was not.")
      }

    case RangeQueryExpression(rangeWrapper) =>
      val range = rangeWrapper match {
        case s: PrefixSeekRangeExpression =>
          s.range.map(expression => makeValueNeoSafe(expression(m)(state)))

        case InequalitySeekRangeExpression(innerRange) =>
          innerRange.mapBounds(expression => makeValueNeoSafe(expression(m)(state)))
      }
      index(range).toIterator
  }

  private def lookupNodes(value: Any, index: Any => GenTraversableOnce[Node]) = value match {
    case null =>
      Iterator.empty
    case _ =>
      val neoValue: Any = makeValueNeoSafe(value)
      index(neoValue)
  }
}
