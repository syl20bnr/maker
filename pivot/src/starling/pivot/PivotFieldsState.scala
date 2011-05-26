package starling.pivot

import java.io.Serializable
import starling.utils.StarlingObject
import collection.immutable.TreeMap
import collection.SortedMap
import starling.utils.ImplicitConversions._

object FieldChooserType extends Enumeration {
  type FieldChooserType = Value
  val Filter = Value("Filter")
  val Rows = Value("Rows")
  val Columns = Value("Columns")
  val FieldList = Value("Field List")
}

import starling.pivot.FieldChooserType._

trait Selection extends Serializable{
  def matches(fieldDetails:FieldDetails, value : Any) : Boolean
  def description: String
}

object AllSelection extends Selection with StarlingObject {
  private def readResolve = AllSelection
  override def toString = "AllSelection"

  def matches(fieldDetails:FieldDetails, value: Any) = true
  def description = "All"
}
case class SomeSelection(values:Set[Any]) extends Selection{
  def matches(fieldDetails:FieldDetails, value: Any) = fieldDetails.matches(values, value)
  def description = values.mkString(", ").replaceLast(", ", ", or ")
}

case class GreaterThanSelection(selection: Comparable[_]) extends Selection{
  def matches(fieldDetails:FieldDetails, value: Any) = value match {
    case c: Comparable[_] => c.asInstanceOf[Comparable[Object]].compareTo(selection.asInstanceOf[Comparable[Object]]) > 0
    case _ => throw new Exception("Can't use non-comparable value: " + value)
  }
  def description = "Greater than: " + selection
}

case class Totals(rowGrandTotal:Boolean, rowSubTotals:Boolean, columnGrandTotal:Boolean, columnSubTotals:Boolean) {
  def toggleRowGrandTotal = copy(rowGrandTotal = !rowGrandTotal)
  def toggleColumnGrandTotal = copy(columnGrandTotal = !columnGrandTotal)
  def toggleRowSubTotals = copy(rowSubTotals = !rowSubTotals)
  def toggleColumnSubTotals = copy(columnSubTotals = !columnSubTotals)
  def noSubTotals = copy(rowSubTotals=false, columnSubTotals=false)
}
object Totals {
  def Null = Totals(false, false, false, false)
}

object Position extends Enumeration {
  type Position = Value
  val Top,Left,Bottom,Right,Other = Value
}

case class FiltersList(filters:List[List[(Field,Selection)]]) extends Iterable[List[(Field,Selection)]] {
  def toFilterSet = filters.flatten.toSet
  def allFields = toFilterSet.map(_._1)
  def iterator = filters.iterator
  def chopUpToFirstNon(fields:Set[Field]) = {
    val chopped = filters.map {
      path => {
        val (before,after) = path.span{ case(f,_)=>fields.contains(f) }
        before
      }
    }
    FiltersList(chopped)
  }
}

case class FilterWithOtherTransform(selection:Set[Any])
object FilterWithOtherTransform {
  case object OtherValue { override def toString = "Other" }
  val Other = OtherValue
  def treeNode = (OtherValue, OtherValue.toString)
}


case class ColumnStructurePath(dataField:Option[Field], path:List[(Field, Int)]) {
  def addField(field:Field, index:Int) = ColumnStructurePath(dataField, (field, index) :: path)

  def addDataField(field:Field, index:Int) = dataField match {
    case None => ColumnStructurePath(Some(field), (field, index) :: path)
    case Some(df) => throw new Exception("There are two datafields in the path " + df + " and " + field)
  }

  def join (next:ColumnStructurePath)= {
    val newDataField = (dataField, next.dataField) match {
      case (Some(_), Some(_)) => throw new Exception(this + " and "  + next + " both have measures")
      case (None, Some(_)) => next.dataField
      case (Some(_), None) => dataField
      case _ => None
    }
    ColumnStructurePath(newDataField, path ::: next.path)
  }
}

// Only used for schema evolution.
case class ColumnStructure(field:Field, isData:Boolean, children:List[ColumnStructure]) {
  def toColumnTree:ColumnTree = {
    ColumnTree(field, isData, ColumnTrees(children.map(_.toColumnTree)))
  }
}

object ColumnTrees {
  val Null = new ColumnTrees(List[ColumnTree]())
  val TempField = Field("temp")
  def dataField(field:Field) = ColumnTrees(field, true)

  def createFlat(columnFields:List[Field], dataFields:List[Field]) = {
    var cses = dataFields.map(f=>ColumnTree(f, true))
    columnFields.reverse.foreach(f=> cses = List(ColumnTree(f, false, cses : _*)))
    ColumnTrees(cses)
  }

  def apply(field:Field, isData:Boolean, trees:List[ColumnTree]=Nil):ColumnTrees = {
    ColumnTrees(List(ColumnTree(field, isData, ColumnTrees(trees))))
  }
  def apply() = Null
  //def apply(list:List[ColumnStructure]) = new ColumnStructure(list.flatMap(_.trees))
  def apply(tree:ColumnTree) = new ColumnTrees(List(tree))
}

case class FieldAndIsMeasure(field:Field, isMeasure:Boolean)

case class FieldOrColumnStructure(value:Either[FieldAndIsMeasure,ColumnTrees]) {
  value match {
    case Right(cs) => /*assert(cs.trees.size > 1, "Not enough trees (" + cs.trees.size + ")")*/
    case _ =>
  }
  def remove(field:Field) = {
    value match {
      case Left(f) if f.field != field => this
      case Left(f) => throw new Exception("Can't remove the field (" + field.name + ") from " + this)
      case Right(cs) => FieldOrColumnStructure(cs.remove(field))
    }
  }
  def rename(field:Field, name:String) = {
    value match {
      case Left(f) if f.field != field => this
      case Left(f) => FieldOrColumnStructure(Field(name), f.isMeasure)
      case Right(cs) => FieldOrColumnStructure(cs.rename(field, name))
    }
  }
}

object FieldOrColumnStructure {
  def apply(field:Field, isMeasure:Boolean):FieldOrColumnStructure = FieldOrColumnStructure(Left(FieldAndIsMeasure(field,isMeasure)))
  def apply(columnStructure:ColumnTrees):FieldOrColumnStructure = FieldOrColumnStructure(Right(columnStructure))
}

case class ColumnTree(fieldOrColumnStructure:FieldOrColumnStructure, childStructure:ColumnTrees) {
  def hasMeasure = {
    fieldOrColumnStructure.value match {
      case Left(FieldAndIsMeasure(_, isData)) => isData
      case Right(_) => false
    }
  }
  def allFieldAndIsMeasures:List[FieldAndIsMeasure] = {
    (fieldOrColumnStructure.value match {
      case Left(f) => List(f)
      case Right(cs) => cs.allFieldAndIsMeasures
    }) ::: childStructure.allFieldAndIsMeasures
  }
  def keep(fields:Set[Field]):List[ColumnTree] = {
    (fieldOrColumnStructure.value match {
      case Left(FieldAndIsMeasure(field,_)) if (fields.contains(field)) => List(ColumnTree(fieldOrColumnStructure, childStructure.keep(fields)))
      case Left(f) => childStructure.keep(fields).trees
      case Right(cs) => {
        val newCS = cs.keep(fields)
        val newFieldOrColumnStructure = if (newCS.trees.size == 1 && newCS.trees.head.childStructure.trees.isEmpty) {
          newCS.trees.head.fieldOrColumnStructure
        } else {
          FieldOrColumnStructure(newCS)
        }
        List(ColumnTree(newFieldOrColumnStructure, childStructure.keep(fields)))
      }
    })
  }
  def flipIsData(field:Field) = {
    ColumnTree(
      FieldOrColumnStructure(fieldOrColumnStructure.value match {
        case Right(cs) => Right(cs.flipIsData(field))
        case Left(FieldAndIsMeasure(`field`, isData)) => Left(FieldAndIsMeasure(field, !isData))
        case other => other
      }),
      childStructure.flipIsData(field)
    )
  }
  def rename(field:Field, name:String):ColumnTree = {
    ColumnTree(fieldOrColumnStructure.rename(field, name), childStructure.rename(field, name))
  }
  def isInvalid:Boolean = {
    val hasMeasure = {
      fieldOrColumnStructure.value match {
        case Right(cs) => cs.measureFields.nonEmpty
        case Left(FieldAndIsMeasure(_, true)) => true
        case Left(FieldAndIsMeasure(_, false)) => false
      }
    }
    if (hasMeasure) {
      childStructure.hasMeasureFields
    } else {
      childStructure.isInvalid
    }
  }

}
object ColumnTree {
  def apply(field:Field, isData:Boolean):ColumnTree = new ColumnTree(FieldOrColumnStructure(field, isData), ColumnTrees.Null)
  def apply(field:Field, isData:Boolean, children:ColumnTree*):ColumnTree = ColumnTree(field, isData, new ColumnTrees(children.toList))
  def apply(field:Field, isData:Boolean, childStructure:ColumnTrees):ColumnTree = new ColumnTree(FieldOrColumnStructure(field, isData), childStructure)
  def dataField(field:Field) = apply(field, true)
}

case class ColumnTrees(trees:List[ColumnTree]) {

  def buildPaths(extra:Int=0):List[ColumnStructurePath] = {
    var previousWidth = extra
    trees.flatMap{ tree => {
      val p = buildPaths(tree, previousWidth)
      previousWidth += p.size
      p
    }}
  }

  private def buildPaths(tree:ColumnTree, position:Int):List[ColumnStructurePath] = {
    tree match {
      case ColumnTree(FieldOrColumnStructure(Left(FieldAndIsMeasure(field, true))), ColumnTrees(Nil)) => List(ColumnStructurePath(Some(field), (field, position) :: Nil))
      case ColumnTree(FieldOrColumnStructure(Left(FieldAndIsMeasure(field, false))), ColumnTrees(Nil)) => List(ColumnStructurePath(None, (field, position) :: Nil))
      case ColumnTree(FieldOrColumnStructure(Left(FieldAndIsMeasure(field, true))), cs) => cs.buildPaths().map(_.addDataField(field, position))
      case ColumnTree(FieldOrColumnStructure(Left(FieldAndIsMeasure(field, false))), cs) => cs.buildPaths().map(_.addField(field, position))
      case ColumnTree(FieldOrColumnStructure(Right(csX:ColumnTrees)), cs) => {
        val pathsForTopColumnStructure:List[ColumnStructurePath] = csX.buildPaths(position)
        val pathsForChildren:List[ColumnStructurePath] = cs.buildPaths()
        pathsForTopColumnStructure.flatMap { p => pathsForChildren.map( c=> p join c)}
      }
    }
  }

  def allFieldAndIsMeasures:List[FieldAndIsMeasure] = trees.flatMap(_.allFieldAndIsMeasures)
  def allFields:List[Field] = allFieldAndIsMeasures.map(_.field)
  def columnFields:List[Field] = allFieldAndIsMeasures.filterNot(_.isMeasure).map(_.field)
  def measureFields:List[Field] = allFieldAndIsMeasures.filter(_.isMeasure).map(_.field)
  def hasMeasureFields = measureFields.nonEmpty
  def hasColumnField = false // TODO - tells us whether we can rotate the pivot report.
  def keep(fields:Set[Field]):ColumnTrees = {
    val newTrees = trees.flatMap(_.keep(fields))
    if (newTrees.forall(_.childStructure.trees.isEmpty)) {
      ColumnTrees(newTrees.flatMap(ct => {
        ct.fieldOrColumnStructure.value match {
          case Left(f) => List(ct)
          case Right(cs) => cs.trees
        }
      }))
    } else {
      ColumnTrees(newTrees)
    }
  }
  def addParents(columns:List[Field]):ColumnTrees = {
    columns.reverse match {
      case Nil => this
      case head :: tail => addParent(head).addParents(tail.reverse)
    }
  }
  def rename(field:Field, name:String) = {
    ColumnTrees(trees.map(_.rename(field, name)))
  }
  def addParent(parent:Field) = {
    ColumnTrees(parent, false, this.trees)
  }

  def addDataField(dataField:Field):ColumnTrees = {
    // This is called when double clicking on a measure field - I'll just add it to the far right of the column/measure area for now.
    ColumnTrees(trees ::: List(ColumnTree(dataField, true)))
  }

  def add(newField:Field, newIsData:Boolean, relativeTo:FieldOrColumnStructure, position:Position.Position):ColumnTrees = {
    val topLevel = FieldOrColumnStructure(this)
    if (topLevel == relativeTo) {
      position match {
        case Position.Top => ColumnTrees(ColumnTree(newField, newIsData, this))
        case Position.Bottom => ColumnTrees(ColumnTree(FieldOrColumnStructure(this), ColumnTrees(ColumnTree(newField, newIsData))))
        case Position.Right => ColumnTrees(trees ::: List(ColumnTree(newField, newIsData)))
        case Position.Left => ColumnTrees(ColumnTree(newField, newIsData) :: trees)
      }
    } else {
      ColumnTrees(trees.flatMap { tree => {
        if (tree.fieldOrColumnStructure == relativeTo) {
          position match {
            case Position.Top => List(ColumnTree(newField, newIsData, tree))
            case Position.Bottom => List(ColumnTree(tree.fieldOrColumnStructure, ColumnTrees(List( ColumnTree(newField, newIsData, tree.childStructure)))))
            case Position.Right => {
              if (tree.childStructure.trees.isEmpty) {
                List(tree, ColumnTree(newField, newIsData))
              } else {
                val cs = ColumnTrees(List(tree.copy(childStructure = ColumnTrees.Null), ColumnTree(newField, newIsData)))
                List(ColumnTree(FieldOrColumnStructure(cs), tree.childStructure))
              }
            }
            case Position.Left => {
              if (tree.childStructure.trees.isEmpty) {
                List(ColumnTree(newField, newIsData), tree)
              } else {
                val cs = ColumnTrees(List(ColumnTree(newField, newIsData), tree.copy(childStructure = ColumnTrees.Null)))
                List(ColumnTree(FieldOrColumnStructure(cs), tree.childStructure))
              }
            }
          }
        } else {
          val fixedFOCS = FieldOrColumnStructure(tree.fieldOrColumnStructure.value match {
            case Right(cs) => Right(cs.add(newField, newIsData, relativeTo, position))
            case other => other
          })
          List(ColumnTree(fixedFOCS, tree.childStructure.add(newField, newIsData, relativeTo, position)))
        }
      } })
    }
  }
  def contains(testField:Field):Boolean = {
    allFields.contains(testField)
  }
  def removeAllChildren(fields:Set[Field]):ColumnTrees = {
    keep(allFields.toSet -- fields)
  }
  def remove(removeField:Field):ColumnTrees = {
    keep(allFields.toSet - removeField)
  }
  def flipIsData(fieldToFlip:Field):ColumnTrees = {
    ColumnTrees(trees.map(_.flipIsData(fieldToFlip)))
  }

  def isInvalid:Boolean = {
    trees.exists(_.isInvalid)
  }
  
  def hasPathContaining(fields:Set[Field]) : Boolean = {
    val fieldsInPaths = buildPaths().map(_.path.map(_._1).toSet)
    fieldsInPaths.exists(s => fields.subsetOf(s))
  }
}

//This was a case class but to ensure compatability with old code it is now a normal
//class where the object has an apply method matching the old constructor signature
//for that reason there are many case like methods (ie copy, equals and hashCode)
class PivotFieldsState(
        val rowFields:List[Field]=List(),
        val columns:ColumnTrees=ColumnTrees.Null,
        val filters:List[(Field,Selection)]=List(),
        val treeDepths:SortedMap[Field,(Int,Int)]=TreeMap.empty,
        val reportSpecificChoices:SortedMap[String,Any]=TreeMap.empty,
        val transforms:SortedMap[Field,FilterWithOtherTransform]=TreeMap.empty
  ) extends Serializable {

  assert(filters.size == filters.map(_._1).toSet.size, {Thread.dumpStack; "There are duplicated filter fields"})

  override def toString = "r: " + rowFields + " c: " + columns + " f: " + filters  + " treeDep: " +
          treeDepths + " rpc: " + reportSpecificChoices
  def allFieldsUsed = columns.allFields ::: rowFields ::: filters.map(_._1)

  def filterAreaFields = {
    filters.map(_._1).filterNot{ f=> columns.contains(f) || rowFields.contains(f)}
  }

  def filtersInTheFilterArea = {
    filters.filterNot{ case(f,_) => columns.contains(f) || rowFields.contains(f) }
  }

  def copy(
                  rowFields:List[Field] = rowFields,
                  columns:ColumnTrees = columns,
                  reportSpecificChoices:SortedMap[String,Any]=reportSpecificChoices,
                  filters:List[(Field,Selection)]=filters,
                  treeDepths:SortedMap[Field,(Int,Int)]=treeDepths,
                  transforms:SortedMap[Field,FilterWithOtherTransform]=transforms) = {
    new PivotFieldsState(rowFields, columns, filters, treeDepths, reportSpecificChoices, transforms)
  }

  override def equals(o: Any) = o match {
    case other : PivotFieldsState => {
      this.rowFields == other.rowFields &&
      this.columns == other.columns &&
      this.filters == other.filters &&
      this.treeDepths == other.treeDepths &&
      this.reportSpecificChoices == other.reportSpecificChoices &&
      this.transforms == other.transforms
    }
    case _ => false
  }

  override def hashCode = 1 

  def withFiltersAndRowFields(newFilters:Seq[(Field,Selection)], newAxisFields:PivotAxis) = {
    val dataFieldsToUse = if (newAxisFields.removeDataFields) {
      newAxisFields.dataFields
    } else {
      columns.measureFields ::: newAxisFields.dataFields
    }
    val newFilterFields = newFilters.map(_._1).toSet
    new PivotFieldsState(
      (rowFields.filterNot(newFilters.toList.map(_._1).contains)) ::: newAxisFields.rowFields,
      ColumnTrees.createFlat(newAxisFields.columnFields, dataFieldsToUse),
      filters.filterNot(f=> newFilterFields.contains(f._1)) ::: newFilters.toList,
      treeDepths,
      reportSpecificChoices,
      transforms
    )
  }
  def rotate = new PivotFieldsState(columns.columnFields, ColumnTrees.createFlat(rowFields, columns.measureFields), filters, treeDepths, reportSpecificChoices, transforms)
  def hasRowOrColumnFields = !rowFields.isEmpty || columns.hasColumnField
  def moveField(field:Field, from:FieldChooserType, to:FieldChooserType, pos:Int):PivotFieldsState = {
    val newFilterFields = if (to == FieldList) {
      filters.filter(_._1 != field)
    } else if (to == Filter) {
      val entry = filters.find{case (f,_) => f==field}.getOrElse( (field,AllSelection) )
      val filtersToFilter = filters.filterNot{case (_field,_) => _field==field}
      filtersToFilter.splitAt(pos) match {case (l1, l2) => l1 ::: entry :: l2}
    } else {
      filters
    }

    val cleanRows = if (Rows == from) rowFields.filterNot(_ == field) else rowFields
    val newRowFields = if (Rows == to) cleanRows.splitAt(pos) match { case (l1, l2) => l1 ::: field :: l2} else cleanRows

    val cleanColumns = if (Columns == from) columns.remove(field) else columns
    val newColumns = if (Columns == to) cleanColumns.addDataField(field) else cleanColumns

    val newTransforms = transforms.filter(t => newColumns.contains(t._1) || newRowFields.contains(t._1))

    new PivotFieldsState(newRowFields, newColumns, newFilterFields, treeDepths, reportSpecificChoices, newTransforms)
  }

  def moveField(field:Field, from:FieldChooserType, newColumnStructure:ColumnTrees):PivotFieldsState = {
    // If the field has been changed into a measure field, remove any filters that it might have.
    val newFilters = if (newColumnStructure.measureFields.contains(field)) {
      filters.filterNot{case (_field, _) => _field == field}
    } else {
      filters
    }

    val rFields = if (Rows == from) {
      rowFields.filter(_ != field)
    } else {
      rowFields
    }

    val newTransforms = transforms.filter(t => newColumnStructure.contains(t._1) || rFields.contains(t._1))

    new PivotFieldsState(rFields, newColumnStructure, newFilters, treeDepths, reportSpecificChoices, newTransforms)
  }

  def mapSelectionValues(f:Any=>Any) = {
    val modifiedFilters = filters.map { case (field,selection) => {
      selection match {
        case SomeSelection(values) => {
          field -> SomeSelection(values.map(f))
        }
        case _ => field -> selection
      }
    }}
    copy(filters = modifiedFilters)
  }


  def removeAll(fields:Set[Field]) = {
    new PivotFieldsState(
      rowFields.filterNot(fields.contains(_)),
      columns.removeAllChildren(fields),
      filters.filterNot(f=>fields.contains(f._1)),
      treeDepths,
      reportSpecificChoices,
      transforms.filterNot(f=>fields.contains(f._1))
    )
  }

  def keepAll(validFields:Set[Field]) = {
    new PivotFieldsState(
      rowFields.filter(validFields.contains(_)),
      columns.keep(validFields),
      filters.filter(f=>validFields.contains(f._1)),
      treeDepths,
      reportSpecificChoices,
      transforms.filter(f=>validFields.contains(f._1))
    )
  }

  def addFilter(filter:(Field,Set[Any])) = {
    val newFilters = if (filters.map(_._1).contains(filter._1)) {
      filters.map { case (field,selection) => {
        if (field == filter._1) {
          field -> new SomeSelection(filter._2)
        } else {
          field -> selection
        }
      } }
    } else {
      filter._1 -> SomeSelection(Set(filter._2)) :: filters
    }
    copy(filters=newFilters)
  }

  def allFilterPaths = {
    val columnPaths = columns.buildPaths().map(path => {
      path.path.map(_._1).filter(f => {
        path.dataField match {
          case None => true
          case Some(df) => df != f
        }
      })
    })
    val allPaths: List[scala.List[Field]] = if (rowFields.isEmpty) {
      if (columnPaths.isEmpty) {
        List(Nil)
      } else {
        columnPaths
      }
    } else {
      rowFields :: columnPaths.map(p=>rowFields:::p)
    }

    val filterFieldToSelection = Map() ++ filters.map(t=>t._1->t._2)

    val filtersList = allPaths.map(path => filtersInTheFilterArea ::: path.map {
      field => {
        val selection = filterFieldToSelection.get(field) match {
          case Some(s) => s
          case None => AllSelection
        }
        (field, selection)
      }
    })
    FiltersList(filtersList)
  }

  def fieldSelection(f:Field) = {
    filters.find{ case (field,selection) => field==f && selection.isInstanceOf[SomeSelection]}.map { case (_,SomeSelection(values)) => values }
  }

  def addChoice(choice : (String, Any)) = copy(reportSpecificChoices = reportSpecificChoices + choice)
}

object PivotFieldsState {
  def apply(
        dataFields:List[Field]=List(),
        rowFields:List[Field]=List(),
        columnFields:List[Field]=List(),
        filters:List[(Field,Selection)]=List(),
        treeDepths:SortedMap[Field,(Int,Int)]=TreeMap.empty,
        reportSpecificChoices : SortedMap[String, Any] = TreeMap.empty,
        transforms : SortedMap[Field, FilterWithOtherTransform] = TreeMap.empty
  ) = {
    new PivotFieldsState(
      columns=ColumnTrees.createFlat(columnFields, dataFields),
      rowFields=rowFields,
      filters=filters,
      treeDepths=treeDepths,
      reportSpecificChoices=reportSpecificChoices,
      transforms=transforms
    )
  }
}

case class PivotFieldParams (calculate:Boolean, pivotFieldState:Option[PivotFieldsState])