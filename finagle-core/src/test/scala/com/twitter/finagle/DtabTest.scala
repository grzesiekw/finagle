package com.twitter.finagle

import com.twitter.util.Activity
import java.net.InetSocketAddress
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.{AssertionsForJUnit, JUnitRunner}

@RunWith(classOf[JUnitRunner])
class DtabTest extends FunSuite with AssertionsForJUnit {

  def pathTree(t: String) =
    NameTree.read(t).map(Name(_))

  def assertEquiv[T: Equiv](left: T, right: T) = assert(
    if (Equiv[T].equiv(left, right)) None
    else Some(left + "!=" + right)
  )

  test("Lookup all prefixes in reverse order") {
    val dtab = Dtab.read("/foo/bar=>/xxx;/foo=>/yyy")

    assertEquiv(
      dtab.lookup(Path.read("/foo/bar/baz")).sample(),
      pathTree("/yyy/bar/baz | /xxx/baz"))
  }

  test("Expand names") {
    val dtab = Dtab.read("/foo/bar => /xxx|/yyy&/zzz")

    assertEquiv(
      dtab.lookup(Path.read("/foo/bar/baz")).sample(),
      pathTree("/xxx/baz | /yyy/baz & /zzz/baz"))
  }

  test("d1 ++ d2") {
    val d1 = Dtab.read("/foo => /bar")
    val d2 = Dtab.read("/foo=>/biz;/biz=>/$/inet/0/8080;/bar=>/$/inet/0/9090")

    assert(d1++d2 === Dtab.read("""
      /foo=>/bar;
      /foo=>/biz;
      /biz=>/$/inet/0/8080;
      /bar=>/$/inet/0/9090
    """))

    def assertEval(dtab: Dtab, path: Path, expected: Name.Bound*) {
      val dtab2 = Dtab.read("/=>/#/com.twitter.finagle.namer.global") ++ dtab
      dtab2.bind(NameTree.Leaf(path)).sample().eval match {
        case Some(actual) => assert(actual.map(_.addr.sample) === expected.map(_.addr.sample).toSet)
        case _ => assert(false)
      }
    }

    assertEval(d1 ++ d2, Path.read("/foo"), Name.bound(new InetSocketAddress(8080)))
    assertEval(d2 ++ d1, Path.read("/foo"), Name.bound(new InetSocketAddress(9090)))
  }

  test("d1 ++ Dtab.empty") {
    val d1 = Dtab.read("/foo=>/bar;/biz=>/baz")

    assert(d1 ++ Dtab.empty === d1)
  }

  test("Dtab.stripPrefix") {
    val d1, d2 = Dtab.read("/foo=>/bar;/baz=>/xxx/yyy")

    assert(d1.stripPrefix(d1).isEmpty)
    assert(d1.stripPrefix(d2).isEmpty)

    assertEquiv(
      (d1 + Dentry.read("/foo => /123")).stripPrefix(d1),
      Dtab.read("/foo=>/123"))

    assertEquiv(d1.stripPrefix(d1 + Dentry.read("/s => /b")), d1)
    assert(Dtab.empty.stripPrefix(d1).isEmpty)
  }

  // These are mostly just compilation tests.
  test("Dtab is a Scala collection") {
    val b = Dtab.newBuilder
    b += Dentry.read("/a => /b")
    b += Dentry.read("/c => /d")
    val dtab = b.result

    val dtab1: Dtab = dtab map { case Dentry(a, b) =>
      Dentry.read("%s=>%s".format(a.show.toUpperCase, b.show.toUpperCase))
    }

    assert(dtab1.size === 2)
    dtab1(0) match {
      case Dentry(a, b) =>
        assert(a === Path.Utf8("A"))
        assert(b === NameTree.Leaf(Path.Utf8("B")))
    }
  }

  test("Allows trailing semicolon") {
    val dtab = try {
        Dtab.read("""
          /b => /c;
          /a => /b;
          """)
      } catch { case _: IllegalArgumentException => Dtab.empty }
    assert(dtab.length === 2)
  }

  test("/# path loads Namer class, rewrite is ignored") {
    assert(Dtab.read("/# => /foo").lookup(Path.read("/#/com.twitter.finagle.dtabtest.TestNamer")).sample()
      === NameTree.Leaf(Name.Path(Path.read("/plugh/xyzzy"))))
  }
}

package dtabtest {
  class TestNamer extends Namer {
    def lookup(path: Path) = Activity.value(NameTree.Leaf(Name.Path(Path.read("/plugh/xyzzy"))))
    def enum(prefix: Path) = throw new UnsupportedOperationException
  }
}
