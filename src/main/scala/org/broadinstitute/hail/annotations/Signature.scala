package org.broadinstitute.hail.annotations

import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.broadinstitute.hail.expr
import org.broadinstitute.hail.Utils._

abstract class Signature {
  def dType: expr.Type

  def dType(path: List[String]): expr.Type = {
    if (path.isEmpty)
      dType
    else
      throw new AnnotationPathException()
  }

  def typeCheck(a: Annotation): Boolean = {
    dType.typeCheck(a)
  }

  def parser(missing: Set[String], colName: String): String => Annotation = {
      dType match {
        case expr.TDouble =>
          (v: String) =>
            try {
              if (missing(v)) null else v.toDouble
            } catch {
              case e: java.lang.NumberFormatException =>
                fatal( s"""java.lang.NumberFormatException: tried to convert "$v" to Double in column "$colName" """)
            }
        case expr.TInt =>
          (v: String) =>
            try {
              if (missing(v)) null else v.toInt
            } catch {
              case e: java.lang.NumberFormatException =>
                fatal( s"""java.lang.NumberFormatException: tried to convert "$v" to Int in column "$colName" """)
            }
        case expr.TBoolean =>
          (v: String) =>
            try {
              if (missing(v)) null else v.toBoolean
            } catch {
              case e: java.lang.IllegalArgumentException =>
                fatal( s"""java.lang.IllegalArgumentException: tried to convert "$v" to Boolean in column "$colName" """)
            }
        case expr.TString =>
          (v: String) =>
            if (missing(v)) null else v
        case _ => throw new UnsupportedOperationException(s"Cannot generage a parser for $dType")
      }
  }

  def getOption(fields: String*): Option[Signature] = getOption(fields.toList)

  def getOption(path: List[String]): Option[Signature] = {
    if (path.isEmpty)
      Some(this)
    else
      None
  }

  def delete(fields: String*): (Signature, Deleter) = delete(fields.toList)

  def delete(path: List[String]): (Signature, Deleter) = {
    if (path.nonEmpty) {
      (this, a => a)
    }
    else
      (null, null)
  }

  def insert(signature: Signature, fields: String*): (Signature, Inserter) = insert(signature, fields.toList)

  def insert(signature: Signature, path: List[String]): (Signature, Inserter) = {
    if (path.nonEmpty) {
      StructSignature(Map.empty).insert(signature, path)
    } else
      (this, (a, toIns) => toIns.getOrElse(null))
  }

  def query(fields: String*): Querier = query(fields.toList)

  def query(path: List[String]): Querier = {
    if (path.nonEmpty)
      throw new AnnotationPathException()
    else
      a => Option(a)
  }

  def getSchema: DataType = {
    dType match {
      case expr.TArray(expr.TInt) => ArrayType(IntegerType)
      case expr.TArray(expr.TDouble) => ArrayType(DoubleType)
      case expr.TArray(expr.TString) => ArrayType(StringType)
      case expr.TString => StringType
      case expr.TInt => IntegerType
      case expr.TLong => LongType
      case expr.TDouble => DoubleType
      case expr.TFloat => FloatType
      case expr.TSet(expr.TInt) => ArrayType(IntegerType)
      case expr.TSet(expr.TString) => ArrayType(StringType)
      case expr.TBoolean => BooleanType
      case expr.TChar => StringType
      case _ => throw new UnsupportedOperationException()
    }
  }

  def printSchema(key: String, nSpace: Int, path: String): String = {
    s"""${" " * nSpace}$key: $dType"""
  }
}

case class StructSignature(m: Map[String, (Int, Signature)]) extends Signature {
  override def dType: expr.Type = expr.TStruct(m.map { case (k, (i, v)) => (k, (i, v.dType)) })

  def size: Int = m.size

  override def getOption(path: List[String]): Option[Signature] = {
    if (path.isEmpty)
      Some(this)
    else
      m.get(path.head)
        .flatMap(_._2.getOption(path.tail))
  }

  override def query(p: List[String]): Querier = {
    if (p.isEmpty)
      a => Some(a)
    else {
      m.get(p.head) match {
        case Some((i, sig)) =>
          val q = sig.query(p.tail)
          a =>
            if (a == null)
              None
            else
              q(a.asInstanceOf[Row].get(i))
        case None => throw new AnnotationPathException()
      }
    }
  }

  override def delete(p: List[String]): (StructSignature, Deleter) = {
    if (p.isEmpty)
      (null, null)
    else {
      val key = p.head
      m.get(key) match {
        case Some((i, s)) =>
          s.delete(p.tail) match {
            case (null, null) =>
              // remove this path, bubble up deletions
              val newStruct = StructSignature((m - key).mapValues { case (index, sig) =>
                if (index > i)
                  (index - 1, sig)
                else
                  (index, sig)
              })
              if (newStruct.size == 0)
                (null, null)
              else {
                val f: Deleter = a =>
                  if (a == null)
                    a
                  else
                    a.asInstanceOf[Row].delete(i)
                (newStruct, f)
              }
            case (sig, deleter) =>
              val newStruct = StructSignature(m + ((key, (i, sig))))
              val f: Deleter = a =>
                if (a == null)
                  a
                else {
                  val r = a.asInstanceOf[Row]
                  r.update(i, deleter(r.get(i)))
                }
              (newStruct, f)
          }
        case None => (this, a => a)
      }
    }
  }

  override def insert(signature: Signature, p: List[String]): (Signature, Inserter) = {
    if (p.isEmpty)
      (signature, (a, toIns) => toIns.getOrElse(null))
    else if (p.length == 1) {
      val key = p.head
      m.get(key) match {
        case Some((i, s)) =>
          val f: Inserter = (a, toIns) =>
            if (a == null)
              Row.fromSeq(Array.fill[Any](m.size)(null))
                .update(i, toIns.getOrElse(null))
            else
              a.asInstanceOf[Row].update(i, toIns.orNull)
          val newStruct = StructSignature(m + ((key, (i, signature))))
          (newStruct, f)
        case None =>
          // append, not overwrite
          val f: Inserter = (a, toIns) => {
            if (a == null)
              Row.fromSeq(Array.fill[Any](m.size)(null))
                .append(toIns.getOrElse(null))
            else
              a.asInstanceOf[Row].append(toIns.getOrElse(null))
          }
          val newStruct = StructSignature(m + ((key, (m.size, signature))))
          (newStruct, f)
      }
    } else {
      val key = p.head
      m.get(key) match {
        case Some((i, s)) =>
          val (sig, ins) = s.insert(signature, p.tail)
          val f: Inserter = (a, toIns) =>
            if (a == null)
              Row.fromSeq(Array.fill[Any](m.size)(null))
                .update(i, ins(null, toIns))
            else {
              val r = a.asInstanceOf[Row]
              r.update(i, ins(r.get(i), toIns))
            }
          val newStruct = StructSignature(m + ((key, (i, sig))))
          (newStruct, f)
        case None => // gotta put it in
          val (sig, ins) = {
            if (p.length > 1)
              StructSignature(Map.empty[String, (Int, Signature)])
                .insert(signature, p.tail)
            else
              signature.insert(signature, p.tail)
          }
          val f: Inserter = (a, toIns) =>
            if (a == null) {
              Row.fromSeq(Array.fill[Any](m.size)(null))
                .append(ins(null, toIns))
            } else
              a.asInstanceOf[Row].append(ins(null, toIns))
          (StructSignature(m + ((key, (m.size, sig)))), f)
      }
    }
  }

  override def getSchema: DataType = {
    val s =
      StructType(m
        .toArray
        .sortBy {
          case (key, (index, sig)) => index
        }
        .map {
          case (key, (index, sig)) =>
            StructField(key, sig.getSchema, nullable = true)
        }
      )
    assert(s.length > 0)
    s
  }

  override def printSchema(key: String, nSpace: Int, path: String): String = {
    val spaces = " " * nSpace
    s"""$spaces$key: $path.<identifier>\n""" +
      m.toArray
        .sortBy {
          case (k, (i, v)) => i
        }
        .map {
          case (k, (i, v)) => v.printSchema(s"""$k""", nSpace + 2, path + "." + k)
          //          keep for future debugging:
          //          case (k, (i, v)) => v.printSchema(s"""[$i] $k""", nSpace + 2, path + "." + k)
        }
        .mkString("\n")
  }
}

case class EmptySignature(dType: expr.Type = expr.TBoolean) extends Signature {
  override def getSchema: DataType = BooleanType

  override def printSchema(key: String, nSpace: Int, path: String): String = s"""${" " * nSpace}$key: EMPTY"""

  override def query(path: List[String]): Querier = throw new AnnotationPathException()

  override def getOption(path: List[String]): Option[Signature] = None

  override def delete(path: List[String]): (Signature, Deleter) = (this, a => a)
}

case class SimpleSignature(dType: expr.Type) extends Signature

object SimpleSignature {
  def apply(s: String): SimpleSignature = {
    s match {
      case "Double" => SimpleSignature(expr.TDouble)
      case "Int" => SimpleSignature(expr.TInt)
      case "Boolean" => SimpleSignature(expr.TBoolean)
      case "String" => SimpleSignature(expr.TString)
      case _ => fatal(
        s"""Unrecognized type "$s".  Hail supports parsing the following types in annotations:
            |  - Double (floating point number)
            |  - Int  (integer)
            |  - Boolean
            |  - String
            |
            |  Note that the above types are case sensitive.""".stripMargin)
    }
  }
}