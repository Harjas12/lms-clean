package lms.core

import scala.collection.mutable

import lms.core.stub.Adapter
import lms.macros.SourceContext

import Backend._

abstract class Traverser {
  val blockCalls = new mutable.HashMap[Block, Set[Sym]]

  // freq/block computation
  def symsFreq(g: Graph, x: Node): Set[(Def, Double)] = x match {
    case Node(f, "λ", Block(in, y, ein, eff)::_, _) =>
      val a: Option[Block] = None
      eff.deps.map((e: Def) => (e, 100.0)) + ((y, 100.0))
      // case Node(_, "?", c::Block(ac,ae,af)::Block(bc,be,bf)::Nil, _) =>
      // List((c,1.0)) ++ (ae::be::af ++ bf).map(e => (e,0.5))
    case Node(_, "@", f::args, _) =>
      x.hardSyms.map((s: Def) => (s, 1.0))
    case Node(_, "?", c::(a: Block)::(b: Block)::_, eff) =>
      /*
      println("---")
      for (x <- a.used) {
        println(x)
        println(g.globalDefsCache)
        x match {
          case g.Def(op, defs) => println(op)
          case _ => 
        }
      }
      println("---")
       */
      // XXX why eff.deps? would lose effect-only statements otherwise!
      (eff.hdeps.map((e: Def) => (e, 1.0)) + ((c, 1.0))
        ++ (a.used intersect b.used).map((e: Def) => (e, 1.0))
        ++ (a.used diff b.used).map((e: Def) => (e, 0.5))
        ++ (b.used diff a.used).map((e: Def) => (e, 0.5)))
    case Node(_, "W", (a: Block)::(b: Block)::_, eff) =>
      // XXX why eff.deps?
      eff.hdeps.map((e: Def) => (e, 1.0)) ++ (a.used ++ b.used).map(e => (e, 100.0))
    case Node(_, "switch", guard::rhs, eff) =>
      // XXX: 1 / # blocks instead of 0.5?
      val freqs: Set[(Def, Double)] = rhs.foldLeft(Set((guard, 1.0))) {
        case (acc, b: Block) => acc ++ b.used.map(e => (e, 0.5))
        case (acc, _) => acc
      }
      freqs ++ eff.hdeps.map((e: Def) => (e, 1.0))
    case _ => x.hardSyms.map((s: Def) => (s, 1.0))
  }

  /** This `bound` is used to track the dependent bound variable of each node,
    * see the `class Bound` in src/main/scala/lms/core/backend.scala.
    * It is initialized in the `apply()` function of this class.
    */
  val bound = new Bound

  /** This `path` is used to track the available bound variables in the current block.
    * New Syms are pushed into the list when entering a new block, and popped when
    * exiting a block.
    * Its content is maintained by the `withScope` family of functions.
    */
  var path = List[Sym]()

  /** This `inner` is used to track the current unscheduled nodes for this block.
    * For each block, `inner` is splitted into 2 groups, the first one will be scheduled
    * in this block, and the second one will be scheduled later.
    * This splitting is done via the `scheduleBlock_` function.
    * Just as `path`, the content of `inner` is maintained by the `withScope` family of functions.
    */
  var inner: Seq[Node] = _

  // This `blockEffectPath` is currently unused.
  val blockEffectPath = new mutable.HashMap[Block, Set[Exp]]

  /** This `withScope` function maintains the old `path` and `inner` when entering a new block
    * with `newPath` and `newInner`.
    * The block function `block` is passed by name and takes no parameters.
    */
  def withScope[T](newPath: List[Sym], newInner: Seq[Node])(block: => T): T = {
    val (path0, inner0) = (path, inner)
    path = newPath; inner = newInner;
    try block finally { path = path0; inner = inner0 }
  }

  /** This `withScopeCPS` function maintains the old `path` and `inner` when entering a new block
    * with `newPath` and `newInner`.
    * The argument `block` takes a pair of `path` and `inner` as parameters, which are feed with
    * the old `path` and `inner` values.
    * This might seem to be a strange "scoping behavior". Indeed, it is used for CPSTraverser/Transformer.
    * The reason is in continuation-passing style (CPS), the control flow never comes back to the old scope.
    * Instead, it keeps calling the continuations. As the continuations need to use the "old" environment,
    * we have to pass the "old" environment to the `b`.
    * Checkout the `CPSTraverser` and `CPSTransformer` below for more details.
    */
  def withScopeCPS[T](newPath: List[Sym], newInner: Seq[Node])(block: (List[Sym], Seq[Node]) => T): T = {
    val (path0, inner0) = (path, inner)
    path = newPath; inner = newInner;
    try block(path0, inner0) finally { path = path0; inner = inner0 }
  }

  /** This `withResetScope` function maintains the old `inner` when entering a new block with `newInner`.
    * It is so far only used in test6_tensors.scala for ???
    */
  def withResetScope[T](newPath: List[Sym], newInner: Seq[Node])(block: => T): T = {
    assert(path.takeRight(newPath.length) == newPath, s"$path -- $newPath")
    val inner0 = inner
    inner = newInner
    try block finally { inner = inner0 }
  }

  val scheduleCache = new mutable.HashMap[Block, Seq[Node]]

  /** This `scheduleBlock` function wraps the `scheduleBlock_` function.
    * It sets the new path (`path1`) and new inner (`inner1`) environment,
    * and traverses nodes for the current block: `traverse(outer1, y)`
    */
  def scheduleBlock[T](block: Block, extra: Sym*)(traverse: (Seq[Node], Block) => T): T =
    scheduleBlock_(block, extra: _*) { (path1, inner1, outer1, y) =>
      withScope(path1, inner1) {
        traverse(outer1, block)
      }
    }

  /** This `scheduleBlock_` is the core function in traversal. The main purpose of this function is to
    * separate the currently unscheduled nodes into the `outer1` and `inner1`, where
    * `outer1` is to be scheduled for the current block, and `inner1` is to be scheduled later.
    * It should be noted that this is strongly tied to the fact that LMS IR uses `sea-of-node`
    * representation, where the blocks do not explicitly scope the nodes. Instead, the nodes
    * in each block are collected lazily from this scheduleBlock_ function, from the result and effects
    * of the (to be scheduled) block.
    * The function takes another function `f` as curried parameter, which is applied with
    * the new path, new inner, new outer, and the block.
    */
  def scheduleBlock_[T](block: Block, extra: Sym*)(f: (List[Sym], Seq[Node], Seq[Node], Block) => T): T = {
    // When entering a block, we get more bound variables
    // (from the block and possibly supplemented via `extra`)
    val path1 = block.bound ++ extra.toList ++ path

    // a node is available if all bound vars the node depends on are in the scope
    def available(d: Node) = bound.hm(d.n) -- path1 - d.n == Set()

    val g = new Graph(inner, block, inner.map((n: Node) => (n.n, n)).toMap)

    // Step 1: compute `reach` and `reachInner`
    //   These are nodes that are reachable from for the current block and for an inner block
    //   We start from `block.used`, where `block` is the current block as roots, and backtrack
    //   all nodes that are hard-depended from the roots.
    //   The `reachInner` uses reachable Syms that have low frequency as roots.
    val reach = new mutable.HashSet[Sym]
    val reachInner = new mutable.HashSet[Sym]
    reach ++= block.used

    // Note (GW): the complexity of this traversal seems to be O(n^2): each time we split
    // the set of nodes into two, and next time we apply the same procedure on reachInner.
    // The number of traveral times T(n) = n + c_1 * n + c_2 * n + c_3 * n + ... + c_m * n,
    // where c_i is the ratio of splitting.
    for (d <- g.nodes.reverseIterator) {
      if (reach.contains(d.n)) {
        // println("  reach: " + d)
        if (available(d)) {
          //println("  avaiable: " + d)
          // node will be scheduled here, don't follow if branches!
          // other statement will be scheduled in an inner block
          for ((e:Sym, f) <- symsFreq(g, d))
            if (f > 0.5) reach += e else reachInner += e
        } else {
          // println("  not avaiable: " + d)
          // QUESTION(feiw): why we don't split via frequency here?
          reach ++= d.hardSyms
        }
      } else if (reachInner.contains(d.n)) {
        // println("  reachInner: " + d)
        reachInner ++= d.hardSyms
      } else {
        // println("  neither: " + d)
        // Not reachable by any means?
      }
    }

    /**  NOTES ON RECURSIVE SCHEDULES
      *
      *  E.g. from tutorials/AutomataTest:
      *     x2 = fwd; x23 = DFAState(x2); x2' = (λ ...); x23
      *
      *  PROBLEM: Since we iterate over g.nodes in reverse order, the lambda (x2')
      *  is initially not reachable (only the fwd node x2)!
      *
      *  Ideally we would directly want to build something like
      *     x23 = fwd; x2 = (λ ...) ; x23 = DFAState(x2)
      *  but this seems hard to achieve in general.
      *
      *  We fixed it so far by using a different symbol inside and outside the
      *  function, so now we get:
      *     x2 = fwd; x23 = DFAState(x2); x2' = (λ ...) ; x33 = DFAState(x2')
      *
      *  This is less ideal in terms of CSE, but at least we get a correct
      *  schedule.
      *
      *  We could improve this by using a proper call to SCC instead of just
      *  iterating over g.nodes in reverse order. The performance implications
      *  aren't clear, so we decided to postpone this.
      *
      *  Code would look like this:
      *
      *  def find(s: Sym) = g.nodes.reverse.find(_.n == s).toList
      *  def succ(s: Sym) = {
      *    find(s).flatMap { d =>
      *    if (available(d)) symsFreq(d) collect { case (e:Sym,f) if f > 0.5 => e }
      *    else symsFreq(d) collect { case (e:Sym,f) => e }
      *  }}
      *
      *  val nodes1 = lms.util.GraphUtil.stronglyConnectedComponents(g.block.used, succ)
      *
      *  nodes1.foreach(println)
      *
      *  def tb(x: Boolean) = if (x) 1 else 0
      *  for (n <- inner) {
      *    println(s"// ${tb(available(n))} ${tb(reach(n.n))} $n ${symsFreq(n)}")
      *  }
      */

    /** Should a node `d` be scheduled here? It must be:
      *  (1) available -- not dependent on other bound vars.
      *  (2) used at least as often as the block result.
      * Note: scheduleHere is not used currently.
      */
    def scheduleHere(d: Node) = available(d) && reach(d.n)

    /** Step 2: with the computed `reach` and `reachInner`, we can split the nodes
      * to `outer1` (for current block) and `inner1` (for inner blocks).
      * The logic is simply: `outer1` has nodes that are reachable and available.
      */
    var outer1 = Seq[Node]()
    var inner1 = Seq[Node]()

    /** Extra reachable statements from soft dependencies.
      *  It is important to track softDeps too, because if we don't, the soft-dependent
      *    nodes might go to `inner1` and be scheduled after the node that soft-depends on it.
      *  If a node is only soft-depended by other nodes, we make sure that we can remove it
      *    by DCE pass before traversal passes. (see DeadCodeElimCG class in codegen.scala)
      *  The test "extraThroughSoft_is_necessary" show cases the importance of `extraThroughSoft`.
      */
    val extraThroughSoft = new mutable.HashSet[Sym]
    for (n <- inner.reverseIterator) {
      if (reach(n.n) || extraThroughSoft(n.n)) {
        if (available(n)) {
          outer1 = n +: outer1
          if (!reach(n.n)) // if added through soft deps, hards needs to be added as well
            extraThroughSoft ++= n.syms
          else
            extraThroughSoft ++= n.eff.sdeps
        } else {
          inner1 = n +: inner1
        }
      } else if (reachInner.contains(n.n)) {
        inner1 = n +: inner1
      }
    }

    // These Prints Are Very Useful for Debugging!
    System.out.println(s"================ $block ==============")
    for (n <- inner)
      System.out.println(s"\t$n")
    System.out.println(s"==== Outer ====")
    for (n <- outer1)
      System.out.println(s"\t$n")
    System.out.println(s"==== inner ====")
    for (n <- inner1)
      System.out.println(s"\t$n")
    System.out.println(s"==== path ====")
    for (n <- path1)
      System.out.println(s"\t$n")
    
    f(path1, inner1, outer1, block)
  }

  def traverse(ns: Seq[Node], res: Block): Unit = {
    ns.foreach(traverse)
  }

  def traverse(b: Block, extra: Sym*): Unit = {
    scheduleBlock(b, extra:_*)(traverse)
  }

  def getFreeVarBlock(y: Block, extra: Sym*): Set[Sym] =
    scheduleBlock(y, extra:_*) { (ns: Seq[Node], res: Block) =>
      val used = new mutable.HashSet[Sym]
      val bound = new mutable.HashSet[Sym]
      used ++= y.used
      bound ++= path
      for (n <- ns ++ inner) {
        used ++= n.syms
        bound += n.n
        bound ++= n.boundSyms
      }
      (used diff bound).toSet
    }

  def traverse(n: Node): Unit = n match {
    case n @ Node(f, "λ", (y:Block)::_, _) =>
      // special case λ: add free var f
      traverse(y, f)
    case n @ Node(f, op, es, _) =>
      // generic traversal: go into all blocks
      for (e @ Block(_,_,_,_) <- es)
        traverse(e)
  }

  def apply(g: Graph): Unit = {
    bound(g)
    withScope(Nil, g.nodes) {
      traverse(g.block)
    }
  }
}

/** CPSTraverser is an adaptation of regular Traverser, where the traverse calls
  * are carried out in Continuation-Passing Style (CPS). The CPS style is featured
  * by the `k` parameter of each `traverse` function, which is the `continuation`
  * after each `traverse` function. The main idea is that for each `traverse` call,
  * what needs to happen after are captured in the `continuation`, such that when
  * a `traverse` function returns, the traverse of all the nodes are done already.
  */
abstract class CPSTraverser extends Traverser {
  // Note that the continuation of `traverse(block)` need to use the original
  // `path0` and `inner0`, as shown in the `(v => withScope(path0, inner0)(k(v)))`
  def traverse(y: Block, extra: Sym*)(k: Exp => Unit): Unit =
    scheduleBlock_(y, extra: _*) { (path1, inner1, outer1, y) =>
      withScopeCPS(path1, inner1) { (path0, inner0) =>
        traverse(outer1, y){ v => withScope(path0, inner0)(k(v)) }
      }
    }

  // Similarly, when traversing a list of nodes or blocks (the next function),
  // the rest of the blocks are traversed in the continuation of the first node/block
  def traverse(ns: Seq[Node], y: Block)(k: Exp => Unit): Unit = {
    if (!ns.isEmpty) traverse(ns.head)(traverse(ns.tail, y)(k)) else k(y.res)
  }

  def traverse(bs: List[Block])(k: => Unit): Unit =
    if (!bs.isEmpty) traverse(bs.head)(v => traverse(bs.tail)(k)) else k

  def traverse(n: Node)(k: => Unit): Unit = n match {
    case n @ Node(f, "λ", (y:Block)::_, _) =>
      traverse(y, f)(v => k)
    case n @ Node(f, op, es, _) =>
      traverse(n.blocks)(k)
  }

  def apply(g: Graph)(k: Int): Unit = {
    bound(g)
    path = Nil; inner = g.nodes
    traverse(g.block)(e => {})
  }
}

class CompactTraverser extends Traverser {
  def mayInline(n: Node): Boolean = n match {
    case Node(s, "var_new", _, _) => false
    case Node(s, "local_struct", _, _) => false
    case Node(s, "timestamp", _, _) => false
    case Node(s, "NewArray", List(_, _), _) => false
    case Node(s, "Array", _, _) => false
    case Node(s, "String.##", List(_, _), _) => false
    case Node(s, "comment", _, _) => false
    case Node(s, "array_sort_scala", _, _) => true
    case _ => true
  }

  var shouldInline: Sym => Option[Node] = (_ => None)
  var numStms = 0
  var lastNode: Option[Node] = None

  object InlineSym {
    def unapply(x: Sym) = shouldInline(x)
  }

  override def withScope[T](p: List[Sym], ns: Seq[Node])(b: =>T): T = {
    val save = shouldInline
    val save1 = numStms
    val save2 = lastNode
    try super.withScope(p, ns)(b) finally {
      shouldInline = save; numStms = save1; lastNode = save2
    }
  }

  override def traverse(ns: Seq[Node], block: Block): Unit = {
    // ----- forward pass -----
    // lookup sym -> node for locally defined nodes
    val df = new mutable.HashMap[Sym, Node]

    // how many times a sym is used locally (excluding blocks and effects)
    val hm = new mutable.HashMap[Sym, Int]

    // local successor nodes (including blocks and effects)
    val succ = new mutable.HashMap[Sym, List[Sym]]

    // check if a node is used from some inner scope
    val hmi = new mutable.HashSet[Sym]

    // count how many times a node is used at the current level
    if (block.res.isInstanceOf[Sym])
      hm(block.res.asInstanceOf[Sym]) = 1

    for (n <- ns) {
      df(n.n) = n
      for (s <- n.directSyms if df.contains(s) || n.op == "λforward") // do not count refs through blocks or effects
        hm(s) = hm.getOrElse(s,0) + 1                                 // NOTE: λforward is to deal with recursive defs
      for (s <- n.syms if df.contains(s))
        succ(s) = n.n::succ.getOrElse(s,Nil)
      n.blocks.foreach(hmi ++= _.used) // block results count as inner
    }                                  // syms(n) -- directSyms(n)

    for (n <- inner) hmi ++= n.hardSyms

    // NOTE: Recursive lambdas cannot be inlined. To ensure this
    // behavior, we count λforward as additional ref to the lambda
    // in the _current_ scope. There must be at least one non-recursive
    // ref to the lambda: if it is also in the current scope the hm
    // count reaches 2, if it is in an inner scope it is accounted
    // for in hmi. Either case will prevent inlining.
    // An alternative would be to count λforward in hmi.

    val dis = new mutable.HashSet[Sym]

    // should a definition be inlined or let-inserted?
    shouldInline = { (n: Sym) =>
      if ((df.contains(n)) &&             // locally defined
          (hm.getOrElse(n, 0) == 1) &&    // locally used exactly once
          (!hmi(n)))                      // not used in nested scopes
          Some(df(n))
      else None
    }
    // (shouldInline is protected by withScope)

    // ----- backward pass -----

    // for nodes that should be inlined, disable if dependencies interfere
    val seen = new mutable.HashSet[Sym]

    def processNodeHere(n: Node): Unit = {
      seen += n.n
      for (s <- n.directSyms.reverse) {
        checkInline(s)
      }
    }

    def checkInline(res: Sym) = shouldInline(res) match {
      case Some(n) =>
        // want to inline, now check that all successors are already there, else disable
        if (mayInline(n) && succ.getOrElse(n.n,Nil).forall(seen))
          processNodeHere(n)
        else
          df -= n.n
      case _ =>
    }

    if (block.res.isInstanceOf[Sym])
      checkInline(block.res.asInstanceOf[Sym]) // try to inline block.res, state after must be block.eff

    numStms = 0

    val revNs = ns.reverse
    lastNode = revNs.headOption // not
    for (n <- revNs) {
      if (shouldInline(n.n).isEmpty) {
        processNodeHere(n); numStms += 1
      }
    }

    // ----- forward pass -----
    traverseCompact(ns, block)
  }

  def traverseCompact(ns: Seq[Node], y: Block): Unit = {
    // only emit statements if not inlined
    for (n <- ns) {
      if (shouldInline(n.n).isEmpty)
        traverse(n)
    }
  }

  // subclass responsibility:

  // -- disabled here because don't want to fix result type
  def traverseShallow(n: Def): Unit = n match {
    case InlineSym(n) => traverseShallow(n)
    case b: Block => traverse(b)
    case _ => ??? // TODO: Should we throw an error here?
  }

  def traverseShallow(n: Node): Unit = n match {
    case n @ Node(_, op, args, _) =>
      args.foreach(traverseShallow)
  }

  override def traverse(n: Node): Unit = n match {
    case n @ Node(f, "λ", (y:Block)::_, _) =>
      // special case λ: add free var f
      traverse(y,f)
    case n @ Node(f, op, es, _) =>
      // generic traversal
      es.foreach(traverseShallow)
  }
}

abstract class Transformer extends Traverser {
  var g: GraphBuilder = null

  // FIXME(feiw) maybe we should fix typeMap when we add to subst?
  val subst = new mutable.HashMap[Sym,Exp]

  def transform(s: Exp): Exp = s match {
    case s @ Sym(_) if subst contains s => subst(s)
    case s @ Sym(_) => println(s"Warning: not found in subst $subst: "+s); s
    case a => a // must be const
  }

  def transform(b: Block): Block = b match {
    case b @ Block(Nil, res, block, eff) =>
      g.reify {
        //subst(block) = g.effectToExp(g.curBlock) //XXX
        traverse(b); transform(res)
      }
    case b @ Block(arg::Nil, res, block, eff) =>
      g.reify { e =>
        if (subst contains arg)
          println(s"Warning: already have a subst for $arg")
        try {
          subst(arg) = e
          //subst(block) = g.effectToExp(g.curBlock) //XXX
          traverse(b)
          transform(res)
        } finally subst -= arg
      }
    case _ => ???
  }

  def transform(n: Node): Exp = n match {
    case Node(s, "λ", (b @ Block(in, y, ein, eff))::_, _) =>
      // need to deal with recursive binding!
      val s1 = Sym(g.fresh)
      subst(s) = s1
      g.reflect(s1, "λ", transform(b))()
    case Node(s,op,rs,es) =>
      // effect dependencies in target graph are managed by
      // graph builder, so we drop all effects here
      val (effects,pure) = (es.deps,rs)
      val args = pure.map {
        case b @ Block(_,_,_,_) =>
          transform(b)
        case s : Exp =>
          transform(s)
        case a =>
          a
      }
      // NOTE: we're not transforming 'effects' here (just the keys)
      if (effects.nonEmpty)
        g.reflectEffect(op,args:_*)(es.rkeys.map(transform).toSeq:_*)(es.wkeys.map(transform).toSeq:_*)
      else
        g.reflect(op,args:_*)
  }

  override def traverse(n: Node): Unit = {
    subst(n.n) = transform(n)
    // println(s"transformed ${n.n}->${subst(n.n)}")
  }

  var graphCache: collection.Map[Sym, Node] = _
  var oldSourceMap: mutable.Map[Exp, SourceContext] = _

  def transform(graph: Graph): Graph = {
    // XXX unfortunate code duplication, either
    // with traverser or with transform(Block)

    // graphCache may be use during transformation to check the Node of a Sym
    graphCache = graph.globalDefsCache

    // Handling MetaData 1. save oldTypeMap/SourceMap first
    val oldTypeMap = Adapter.typeMap
    oldSourceMap = Adapter.sourceMap
    // Handling MetaData 2. initialize MetaData as fresh, so the transformer might add new metadata entries
    Adapter.typeMap = new mutable.HashMap[Backend.Exp, Manifest[_]]()
    Adapter.sourceMap = new mutable.HashMap[Backend.Exp, SourceContext]()

    val block = g.reify { e =>
      assert(graph.block.in.length == 1)
      subst(graph.block.in(0)) = e
      // subst(graph.block.ein) = g.curBlock.head // XXX
      super.apply(graph)
      transform(graph.block.res)
    }

    // Handling MetaData 3. update new metadata with old metadata
    for ((k, v) <- subst if v.isInstanceOf[Sym] && oldTypeMap.contains(k))
      Adapter.typeMap(v) = oldTypeMap(k)
    for ((k, v) <- subst if v.isInstanceOf[Sym] && oldSourceMap.contains(k))
      Adapter.sourceMap(v) = oldSourceMap(k)

    Graph(g.globalDefs,block, g.globalDefsCache)
  }
}

abstract class CPSTransformer extends Transformer {
  val forwardMap = mutable.Map[Sym, Sym]()  // this Map set up connection for lambda-forward node (sTo -> sFrom)
  val forwardCPSSet = mutable.Set[Exp]()    // this Set collect sFrom, whose sTo has CPS effect
  val contSet = mutable.Set.empty[Exp]      // this Set collect all continuation captured by shift1 (so that their application doesn't take more continuations)

  def withSubst(s: Sym)(e: => Exp) = { subst(s) = e; subst(s) }  // syntactic helper
  def withSubstScope(args: Sym*)(actuals: Exp*)(k: => Exp) = {
    args foreach { arg => if (subst contains arg) println(s"Warning: already have a subst for $arg") }
    try {
      args.zip(actuals).foreach{ case (arg, e) => subst(arg) = e}; k
    } finally args.foreach{ arg => subst -= arg }
  }

  def traverse(y: Block, extra: Sym*)(k: Exp => Exp): Exp =
    scheduleBlock_(y, extra: _*) { (path1, inner1, outer1, y) =>
      withScopeCPS(path1, inner1) { (path0, inner0) =>
        traverse(outer1, y){ v => withScope(path0, inner0)(k(v)) }
      }
    }

  def traverse(ns: Seq[Node], y: Block)(k: Exp => Exp): Exp = {
    if (!ns.isEmpty) traverse(ns.head)(traverse(ns.tail, y)(k)) else k(transform(y.res))
  }

  def transform(b: Block)(k: Exp => Exp): Block =
    g.reify(b.in.length, (es: List[Exp]) =>
      withSubstScope(b.in:_*)(es:_*){
        traverse(b)(k)
      })

  // need to add additional input to the block, XXX CAN SIMPLIFY ?
  def transformLambda(b: Block): Block = {
    val c = Sym(g.fresh)
    val block = transform(b)(v => g.reflectWrite("@",c,v)(Adapter.CTRL))
    Block(c::block.in, block.res, block.ein, block.eff)
  }

  def reflectHelper(es: EffectSummary, op: String, args: Def*): Exp =
    if (es.deps.nonEmpty)
      g.reflectEffect(op, args: _*)(es.rkeys.map(transform).toSeq:_*)(es.wkeys.map(transform).toSeq:_*)
    else
      g.reflect(op, args:_*)

  def traverse(n: Node)(k: => Exp): Exp = n match {

    case n @ Node(s,"shift1",List(y:Block),es) =>
      contSet += y.in.head
      subst(y.in.head) = g.reflectEffect("λ", g.reify(e => withSubstScope(s)(e)(k)))()()
      traverse(y)(v => v)

    case n @ Node(s,"reset1",List(y:Block),_) =>
      subst(s) = g.reflectWrite("reset0", transform(y)(v => v))(Adapter.CTRL)
      k

    case Node(s,"λ", (b: Block)::_, es) =>
      if (subst contains s) { // "subst of $s has be handled by lambda forward to be ${subst(s)}"
        if (b.eff.keys contains Adapter.CPS) forwardCPSSet += forwardMap(subst(s).asInstanceOf[Sym])
        val s1: Sym = subst(s).asInstanceOf[Sym]
        g.reflect(s1, "λ", transformLambda(b))(hardSummary(forwardMap(s1)))
      } else {
        subst(s) = g.reflect("λ", transformLambda(b))
      }
      k

    case Node(f,"?",c::(a:Block)::(b:Block)::_,es) =>
      val sIf = g.reflectWrite("λ", g.reify(e => withSubstScope(f)(e)(k)))(Adapter.CTRL) // XXX without this Effect, If branch is repeated!!
      val kIf = (v:Exp) => g.reflectWrite("@",sIf,v)(Adapter.CTRL)
      withSubst(f) {
        reflectHelper(es, "?", c match {case c: Exp => transform(c); case c => ???},
          transform(a)(kIf), transform(b)(kIf))
      }

    case Node(f,"W",(c:Block)::(b:Block)::e, es) =>
      val sLoop = Sym(g.fresh)
      g.reflect(sLoop, "λ", transform(c)(v =>
        reflectHelper(es, "?", v, transform(b)(v =>
          g.reflectWrite("@", sLoop)(Adapter.CTRL)), g.reify(k))))(writeSummary(Adapter.CTRL))
      withSubst(f)(reflectHelper(es, "@", sLoop))

    case n @ Node(s,"@",(x:Exp)::(y:Exp)::_,es) if !contSet.contains(x) =>
      val cont = reflectHelper(es, "λ", g.reify{e => subst(s) = e; k})
      withSubst(s)(reflectHelper(es, "@", transform(x), cont, transform(y)))

    case Node(s,"λforward",List(y:Sym, arity), _) =>
      assert(!(subst contains y), "should not have handled lambda yet")
      val sFrom = Sym(g.fresh); val sTo = Sym(g.fresh)
      subst(s) = sFrom; subst(y) = sTo
      forwardMap(sTo) = sFrom
      g.reflect(sFrom, "λforward", sTo, arity)()
      k

    case Node(s,op,rs,es) =>
      subst(s) = reflectHelper(es, op, rs.map {
        case b: Block => transform(b)(v => v)
        case s: Exp => transform(s)
        case a => a
      }:_*); k
  }

  def applyExp(graph: Graph): Exp = {
    bound(graph)
    withScope(Nil, graph.nodes) {
      traverse(graph.block)(v => g.reflectWrite("exit", v)(Adapter.CTRL))
    }
  }

  override def transform(graph: Graph): Graph = {
    // XXX unfortunate code duplication, either
    // with traverser or with transform(Block)
    val block = g.reify { e =>
      assert(graph.block.in.length == 1)
      subst(graph.block.in(0)) = e
      applyExp(graph)
    }
    Graph(g.globalDefs, block, g.globalDefsCache)
  }
}

abstract class SelectiveCPSTransformer extends CPSTransformer {

  override def traverse(n: Node)(k: => Exp): Exp = n match {

    case Node(s,"shift1",List(y:Block),_) => super.traverse(n)(k)
    case Node(s,"reset1",List(y:Block),_) => super.traverse(n)(k)
    case Node(s,"λforward", _, _) => super.traverse(n)(k)
    case Node(f,"?",c::(a:Block)::(b:Block)::_,es) if (es.keys contains Adapter.CPS) => super.traverse(n)(k)
    case Node(f,"W",(c:Block)::(b:Block)::e, es) if (es.keys contains Adapter.CPS) => super.traverse(n)(k)
    // the es.keys of "@" node may have Adapter.CPS, if and only if the lambda has Adapter.CPS
    case Node(s,"@",(x:Exp)::(y:Exp)::_,es) if (es.keys.contains(Adapter.CPS) ||
      forwardCPSSet.contains(subst(x.asInstanceOf[Sym]))) => super.traverse(n)(k)
    // lambda need to capture the CPS effect of its body block
    case Node(s,"λ", List(b: Block),es) if (b.eff.keys contains Adapter.CPS) => super.traverse(n)(k)

    case Node(s,"λ", List(b: Block),es) =>
      if (subst contains s) { // "subst of $s has be handled by lambda forward to be ${subst(s)}"
        val s1: Sym = subst(s).asInstanceOf[Sym]
        g.reflect(s1, "λ", transform(b)(v => v))(hardSummary(forwardMap(s1)))
      } else {
        subst(s) = g.reflect("λ", transform(b)(v => v))
      }
      k

    case Node(s,op,rs,es) => // catch-all case is not calling super, but transforming everything without CPS
      subst(s) = reflectHelper(es, op, rs.map {
        case b: Block => transform(b)(v => v)
        case s: Exp => transform(s)
        case a => a
      }:_*);
      k
  }
}
