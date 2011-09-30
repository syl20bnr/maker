package starling.pivot

import model.UndefinedValue
import starling.quantity._
import scalaz.Scalaz._
import starling.utils.ImplicitConversions._
import collection.SortedMap
import starling.utils.sql.PersistAsBlob


object Row {
  def apply(entries: (Field, Any)*) = new Row(entries.toMap)
  def apply(field: Field, value: Any): Row = apply((field, value))

  def singleRow(rows: List[Row], dataTypeName: String): Row = rows match {
    case row :: Nil => row
    case Nil => throw new Exception("Can't create " + dataTypeName + " from no rows")
    case _ => throw new Exception("Can't create " + dataTypeName + " from more than one row " + rows)
  }

  def create(rows: Traversable[Map[Field, Any]]): List[Row] = rows.map(Row(_)).toList
}

case class Row(value: Map[Field, Any]) {
  lazy val dbValue = new PersistAsBlob(value.mapKeys(_.name).sorted)

  def +(entry: (Field, Any)): Row = copy(value + entry)
  def +(other: Row) = copy(value ++ other.value)
  def +?(entry: (Field, Option[Any])): Row = entry._2.fold(any => this + (entry._1 → any), this)
  def +?(entry: Option[(Field, Any)]): Row = entry.fold(this + _, this)
  def ++(other: Map[Field, Any]): Row = copy(value ++ other)

  def map(f: (Field, Any) => (Field, Any)): Row = copy(value.map(kv => f(kv._1, kv._2)))
  def filterKeys(p: (Field) => Boolean): Row = copy(value.filterKeys(p))

  def matches(field: Field, selection: SomeSelection): Boolean = value.get(field).fold(v => selection.values.contains(v), false)
  def isEmpty = value.isEmpty

  def isDefined(field: Field) = value.get(field) match {
    case None => false
    case Some(UndefinedValue) => false
    case Some(_) => true
  }

  def apply[T](fieldDetails: FieldDetails): T = apply(fieldDetails.field)
  def apply[T](field: Field): T = value(field).asInstanceOf[T]
  def get[T](fieldDetails: FieldDetails): Option[T] = get(fieldDetails.field)
  def get[T](field: Field): Option[T] = value.get(field).asInstanceOf[Option[T]]

  def double(fieldDetails: FieldDetails): Double = apply[Double](fieldDetails)
  def quantity(fieldDetails: FieldDetails): Quantity = apply[Quantity](fieldDetails)
  def string(fieldDetails: FieldDetails): String = apply[String](fieldDetails)

  def pivotQuantity(fieldDetails: FieldDetails): PivotQuantity = apply[Any](fieldDetails) match {
    case q: Quantity => PivotQuantity(q)
    case pq: PivotQuantity => pq
  }
}