package anseliq.domain.service

import scala.util.Random

class SubsetSumSuite extends munit.FunSuite:
  val subsetSum = SubsetSum()

  // canSum tests

  test("canSum returns true when a subset sums to target"):
    assert(subsetSum.canSum(Array(3, 34, 4, 12, 5, 2), 9))

  test("canSum returns false when no subset sums to target"):
    assert(!subsetSum.canSum(Array(3, 34, 4, 12, 5, 2), 30))

  test("canSum returns true when target is zero"):
    assert(subsetSum.canSum(Array(1, 2, 3), 0))

  test("canSum returns true when single element equals target"):
    assert(subsetSum.canSum(Array(5), 5))

  test("canSum returns false when single element does not equal target"):
    assert(!subsetSum.canSum(Array(5), 3))

  test("canSum returns true when all elements are needed"):
    assert(subsetSum.canSum(Array(1, 2, 3, 4), 10))

  test("canSum handles empty array with target zero"):
    assert(subsetSum.canSum(Array.empty[Int], 0))

  test("canSum returns false for empty array with non-zero target"):
    assert(!subsetSum.canSum(Array.empty[Int], 5))

  // findFirstSubset tests

  test("findFirstSubset returns the first subset by array order"):
    val result = subsetSum.findFirstSubset(Array(1, 2, 3, 4, 5), 5)
    assertEquals(result, Some(List(1, 4)))

  test("findFirstSubset returns first subset for another case"):
    val result = subsetSum.findFirstSubset(Array(3, 34, 4, 12, 5, 2), 9)
    assertEquals(result, Some(List(3, 4, 2)))

  test("findFirstSubset returns None when no subset sums to target"):
    val result = subsetSum.findFirstSubset(Array(3, 34, 4, 12, 5, 2), 30)
    assertEquals(result, None)

  test("findFirstSubset returns empty list when target is zero"):
    val result = subsetSum.findFirstSubset(Array(1, 2, 3), 0)
    assertEquals(result, Some(List.empty[Int]))

  test("findFirstSubset with single element matching target"):
    val result = subsetSum.findFirstSubset(Array(5), 5)
    assertEquals(result, Some(List(5)))

  test("findFirstSubset with single element not matching target"):
    val result = subsetSum.findFirstSubset(Array(5), 3)
    assertEquals(result, None)

  test("findFirstSubset with empty array and non-zero target"):
    val result = subsetSum.findFirstSubset(Array.empty[Int], 5)
    assertEquals(result, None)

  test("findFirstSubset with empty array and zero target"):
    val result = subsetSum.findFirstSubset(Array.empty[Int], 0)
    assertEquals(result, Some(List.empty[Int]))

  test("findFirstSubset prefers earlier elements"):
    // Both {2, 4} and {6} sum to 6, but {2, 4} comes first by array order
    val result = subsetSum.findFirstSubset(Array(2, 4, 6), 6)
    assertEquals(result, Some(List(2, 4)))

  // findAllSubsets tests

  test("findAllSubsets returns all valid subsets"):
    val result   = subsetSum.findAllSubsets(Array(1, 2, 3, 4, 5), 5)
    val expected = Set(List(1, 4), List(2, 3), List(5), List(1, 2, 3) /* missing combo? */ ).filter(_.sum == 5)
    // Verify every returned subset sums to target
    assert(result.forall(_.sum == 5))
    // {5}, {2,3}, {1,4}, and we don't expect {1,2,3} since 1+2+3=6
    assertEquals(result.map(_.toSet).toSet, Set(Set(5), Set(2, 3), Set(1, 4)))

  test("findAllSubsets returns empty list when no subset sums to target"):
    val result = subsetSum.findAllSubsets(Array(3, 34, 4, 12, 5, 2), 30)
    assertEquals(result, List.empty)

  test("findAllSubsets returns list with empty list when target is zero"):
    val result = subsetSum.findAllSubsets(Array(1, 2, 3), 0)
    assertEquals(result, List(List.empty[Int]))

  test("findAllSubsets with single element matching target"):
    val result = subsetSum.findAllSubsets(Array(5), 5)
    assertEquals(result, List(List(5)))

  test("findAllSubsets returns multiple overlapping subsets"):
    // {1,5}, {2,4}, {6}
    val result = subsetSum.findAllSubsets(Array(1, 2, 4, 5, 6), 6)
    assert(result.forall(_.sum == 6))
    assertEquals(result.map(_.toSet).toSet, Set(Set(1, 5), Set(2, 4), Set(6)))

  test("findAllSubsets with empty array and non-zero target"):
    val result = subsetSum.findAllSubsets(Array.empty[Int], 5)
    assertEquals(result, List.empty)
