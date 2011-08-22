package starling.utils.conversions
import starling.utils.ImplicitConversions._
import collection.SortedMap
import collection.immutable.TreeMap
import starling.utils.Pattern.Extractor

trait RichMap {
  implicit def enrichMap[K, V](value : Map[K,V]) = new RichMap(value)
  implicit def enrichMultiMap[K, V](value : Map[K, Set[V]]) = new RichMultiMap[K, V](value)
  implicit def enrichNestedMap[K1, K2, V](value: Map[K1, Map[K2, V]]) = new RichMap[K1, Map[K2, V]](value) {
    def flipNesting = value.toList.flatMap { case (k1, k2vs) => k2vs.map { case (k2, v) => (k2, (k1, v)) } }
      .groupInto(_.head, _.tail).mapValues(_.toMap)
  }

  class RichMap[K,V](map : Map[K,V]) {
    def get(key: Option[K]) = key.map(map.get(_)).flatOpt
    def slice(keys : Any*) : Map[K,V] = if (keys.isEmpty) map else map.filterKeys(key => keys.contains(key))
    def mapValue(key: K, f: V => V): Map[K,V] = map.updated(key, f(map(key)))
    def mapKeys[C](f: K => C): Map[C, V] = map.map(kv => (f(kv._1), kv._2))
    def composeKeys[C](f: C => K) = new MapView(map, f)
    def castKeys[C >: K]() = map.asInstanceOf[Map[C, V]]
    def addSome(key: K, value: Option[V]): Map[K,V] = value.map(v => map + key → v).getOrElse(map)
    def addSome(keyValue: (K, Option[V])): Map[K,V] = addSome(keyValue._1, keyValue._2)
    def reverse: Map[V, K] = map.map(_.swap)
    def collectKeys[C](pf: PartialFunction[K, C]): Map[C, V] = map.collect(pf *** identity[V] _)
    def collectValues[W](pf: PartialFunction[V, W]): Map[K, W] = map.collect(identity[K] _ *** pf)
    def collectValuesO[W](f: V => Option[W]): Map[K, W] = map.mapValues(f).collectValues { case value if value.isDefined => value.get }
    def zipMap[W](other: Map[K, W]): Map[K, (V, W)] = {
      val (m, o) = (map.filterKeys(other.keySet), other.filterKeys(map.keySet))
      m.map { case (key, value) => (key, (value, o(key)))}.toMap
    }
    def sortBy(implicit ordering: Ordering[K]): SortedMap[K, V] = TreeMap.empty[K, V](ordering) ++ map
    def sortBy[S](f: K => S)(implicit ordering: Ordering[S]): SortedMap[K, V] = sortBy(ordering.extendTo(f))
    def toExtractor = Extractor.from[K](map.get)
  }

  class RichMultiMap[K, V](map : Map[K, Set[V]]) extends RichMap[K, Set[V]](map) {
    def contains(key : K, value : V) : Boolean = map.get(key).map(_.contains(value)).getOrElse(false)
    def contains(pair : (K, V)) : Boolean = contains(pair._1, pair._2)
  }

  class MapView[K, V, C](map: Map[K, V], keyProjection: C => K) {
    def apply(key: C): V = map.apply(keyProjection(key))
    def get(key: C): Option[V] = map.get(keyProjection(key))
    def contains(key: C): Boolean = map.contains(keyProjection(key))
  }
}