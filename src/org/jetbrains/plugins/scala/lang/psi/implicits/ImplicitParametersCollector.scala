package org.jetbrains.plugins.scala.lang.psi.implicits

import org.jetbrains.plugins.scala.lang.psi.types._
import nonvalue.{Parameter, ScMethodType}
import org.jetbrains.plugins.scala.lang.resolve._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import processor.{ImplicitProcessor, MostSpecificUtil}
import result.{Success, TypingContext}
import com.intellij.psi._
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import params.{ScClassParameter, ScParameter}
import util.PsiTreeUtil
import collection.immutable.HashSet
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScMember}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.extensions.toPsiClassExt
import org.jetbrains.plugins.scala.lang.psi.api.InferUtil
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.SafeCheckException
import annotation.tailrec
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScExistentialClause
import org.jetbrains.plugins.scala.lang.psi.types.Conformance.AliasType

/**
 * @param place        The call site
 * @param tp           Search for an implicit definition of this type. May have type variables.
 *
 * User: Alexander Podkhalyuzin
 * Date: 23.11.2009
 */
class ImplicitParametersCollector(place: PsiElement, tp: ScType, searchImplicitsRecursively: Boolean = true) {
  def collect: Seq[ScalaResolveResult] = {
    var processor = new ImplicitParametersProcessor(false)
    def treeWalkUp(placeForTreeWalkUp: PsiElement, lastParent: PsiElement) {
      if (placeForTreeWalkUp == null) return
      if (!placeForTreeWalkUp.processDeclarations(processor,
        ResolveState.initial(), lastParent, place)) return
      place match {
        case (_: ScTemplateBody | _: ScExtendsBlock) => //template body and inherited members are at the same level
        case _ => if (!processor.changedLevel) return
      }
      treeWalkUp(placeForTreeWalkUp.getContext, placeForTreeWalkUp)
    }
    treeWalkUp(place, null) //collecting all references from scope

    val candidates = processor.candidatesS.toSeq
    if (!candidates.isEmpty) return candidates

    processor = new ImplicitParametersProcessor(true)

    for (obj <- ScalaPsiUtil.collectImplicitObjects(tp, place)) {
      processor.processType(obj, place, ResolveState.initial())
    }

    processor.candidatesS.toSeq
  }

  class ImplicitParametersProcessor(withoutPrecedence: Boolean) extends ImplicitProcessor(StdKinds.refExprLastRef, withoutPrecedence) {
    protected def getPlace: PsiElement = place

    def execute(element: PsiElement, state: ResolveState): Boolean = {
      if (!kindMatches(element)) return true
      val named = element.asInstanceOf[PsiNamedElement]
      val subst = getSubst(state)
      named match {
        case o: ScObject if o.hasModifierProperty("implicit") =>
          if (!ResolveUtils.isAccessible(o, getPlace)) return true
          addResult(new ScalaResolveResult(o, subst, getImports(state)))
        case param: ScParameter if param.isImplicitParameter =>
          param match {
            case c: ScClassParameter =>
              if (!ResolveUtils.isAccessible(c, getPlace)) return true
            case _ =>
          }
          addResult(new ScalaResolveResult(param, subst, getImports(state)))
        case patt: ScBindingPattern => {
          val memb = ScalaPsiUtil.getContextOfType(patt, true, classOf[ScValue], classOf[ScVariable])
          memb match {
            case memb: ScMember if memb.hasModifierProperty("implicit") =>
              if (!ResolveUtils.isAccessible(memb, getPlace)) return true
              addResult(new ScalaResolveResult(named, subst, getImports(state)))
            case _ =>
          }
        }
        case function: ScFunction if function.hasModifierProperty("implicit") => {
          if (!ResolveUtils.isAccessible(function, getPlace)) return true
          addResult(new ScalaResolveResult(named, subst.followed(ScalaPsiUtil.inferMethodTypesArgs(function, subst)), getImports(state)))
        }
        case _ =>
      }
      true
    }

    override def candidatesS: scala.collection.Set[ScalaResolveResult] = {
      val clazz = ScType.extractClass(tp)
      def forFilter(c: ScalaResolveResult): Option[(ScalaResolveResult, ScSubstitutor)] = {
        def compute(): Option[(ScalaResolveResult, ScSubstitutor)] = {
          val subst = c.substitutor
          c.element match {
            case o: ScObject if !PsiTreeUtil.isContextAncestor(o, place, false) =>
              o.getType(TypingContext.empty) match {
                case Success(objType: ScType, _) =>
                  if (!subst.subst(objType).conforms(tp)) None
                  else Some(c, subst)
                case _ => None
              }
            case param: ScParameter if !PsiTreeUtil.isContextAncestor(param, place, false) =>
              param.getType(TypingContext.empty) match {
                case Success(paramType: ScType, _) =>
                  if (!subst.subst(paramType).conforms(tp)) None
                  else Some(c, subst)
                case _ => None
              }
            case patt: ScBindingPattern
              if !PsiTreeUtil.isContextAncestor(ScalaPsiUtil.nameContext(patt), place, false) => {
              patt.getType(TypingContext.empty) match {
                case Success(pattType: ScType, _) =>
                  if (!subst.subst(pattType).conforms(tp)) None
                  else Some(c, subst)
                case _ => None
              }
            }
            case fun: ScFunction if !PsiTreeUtil.isContextAncestor(fun, place, false) => {
              val oneImplicit = fun.paramClauses.clauses.length == 1 && fun.paramClauses.clauses.apply(0).isImplicit
              var doNotCheck = false
              if (!oneImplicit && fun.paramClauses.clauses.length > 0) {
                clazz match {
                  case Some(cl) =>
                    val clause = fun.paramClauses.clauses(0)
                    val funNum = clause.parameters.length
                    val qName = "scala.Function" + funNum
                    val classQualifiedName = cl.qualifiedName
                    if (classQualifiedName != qName && classQualifiedName != "java.lang.Object" &&
                        classQualifiedName != "scala.ScalaObject") doNotCheck = true
                  case _ =>
                }
              }

              if (!doNotCheck) {
                fun.getTypeNoImplicits(TypingContext.empty) match {
                  case Success(funType: ScType, _) => {
                    def checkType(ret: ScType): Option[(ScalaResolveResult, ScSubstitutor)] = {
                      var uSubst = Conformance.undefinedSubst(tp, ret)
                      uSubst.getSubstitutor match {
                        case Some(substitutor) =>
                          def hasRecursiveTypeParameters(typez: ScType): Boolean = {
                            var hasRecursiveTypeParameters = false
                            typez.recursiveUpdate {
                              case tpt: ScTypeParameterType =>
                                fun.typeParameters.find(tp => (tp.name, ScalaPsiUtil.getPsiElementId(tp)) == (tpt.name, tpt.getId)) match {
                                  case None => (true, tpt)
                                  case _ =>
                                    hasRecursiveTypeParameters = true
                                    (true, tpt)
                                }
                              case tp: ScType => (hasRecursiveTypeParameters, tp)
                            }
                            hasRecursiveTypeParameters
                          }

                          for (tParam <- fun.typeParameters) {
                            val lowerType: ScType = tParam.lowerBound.getOrNothing
                            if (lowerType != Nothing) {
                              val substedLower = substitutor.subst(subst.subst(lowerType))
                              if (!hasRecursiveTypeParameters(substedLower)) {
                                uSubst = uSubst.addLower((tParam.name, ScalaPsiUtil.getPsiElementId(tParam)), substedLower, additional = true)
                              }
                            }
                            val upperType: ScType = tParam.upperBound.getOrAny
                            if (upperType != Any) {
                              val substedUpper = substitutor.subst(subst.subst(upperType))
                              if (!hasRecursiveTypeParameters(substedUpper)) {
                                uSubst = uSubst.addUpper((tParam.name, ScalaPsiUtil.getPsiElementId(tParam)), substedUpper, additional = true)
                              }
                            }
                          }

                          uSubst.getSubstitutor match {
                            case Some(uSubstitutor) =>
                              fun.paramClauses.clauses.lastOption match {
                                case Some(clause) if clause.isImplicit =>
                                  //let's find implicit parameters here
                                  try {
                                    //todo: this is hacky solution to not go deeper than 2
                                    //It's important for performance reasons and to avoid SOE
                                    if (searchImplicitsRecursively) {
                                      InferUtil.updateTypeWithImplicitParameters(
                                        ScMethodType(Any, clause.parameters.map(param => {
                                          val p = new Parameter(param)
                                          p.copy(paramType = subst.followed(uSubstitutor).subst(p.paramType))
                                        }), isImplicit = true)(place.getProject, place.getResolveScope), place, check = true,
                                        searchImplicitsRecursively = false
                                      )
                                    }
                                    Some(c.copy(subst.followed(uSubstitutor)), subst)
                                  }
                                  catch {
                                    case s: SafeCheckException =>
                                      Some(c.copy(subst.followed(uSubstitutor), problems = Seq(WrongTypeParameterInferred)), subst)
                                  }
                                case _ => Some(c.copy(subst.followed(uSubstitutor)), subst)
                              }
                            case None => None
                          }

                        //failed to get implicit parameter, there is no substitution to resolve constraints
                        case None => None
                      }
                    }

                    val substedFunType: ScType = subst.subst(funType)
                    if (substedFunType conforms tp) checkType(substedFunType)
                    else {
                      ScType.extractFunctionType(substedFunType) match {
                        case Some(ScFunctionType(ret, params)) if params.length == 0 =>
                          if (!ret.conforms(tp)) None
                          else checkType(ret)
                        case _ => None
                      }
                    }
                  }
                  case _ => None
                }
              } else None
            }
            case _ => None
          }
        }

        import org.jetbrains.plugins.scala.caches.ScalaRecursionManager._

        //todo: find recursion on the types in more complex algorithm
        doComputations(c.element, (tp: Object, searches: Seq[Object]) => {
            searches.find{
              case t: ScType if tp.isInstanceOf[ScType] =>
                if (Equivalence.equivInner(t, tp.asInstanceOf[ScType], new ScUndefinedSubstitutor(), falseUndef = false)._1) true
                else dominates(t, tp.asInstanceOf[ScType])
              case _ => false
            } == None
          }, coreType(tp), compute(), IMPLICIT_PARAM_TYPES_KEY) match {
          case Some(res) => res
          case None => None
        }
      }

      val applicable = super.candidatesS.map(forFilter).flatten
      //todo: remove it when you will be sure, that filtering according to implicit parameters works ok
      val filtered = applicable.filter {
        case (res: ScalaResolveResult, subst: ScSubstitutor) =>
          res.problems match {
            case Seq(WrongTypeParameterInferred) => false
            case _ => true
          }
      }
      val actuals =
        if (filtered.isEmpty) applicable
        else filtered
      new MostSpecificUtil(place, 1).mostSpecificForImplicitParameters(actuals) match {
        case Some(r) => HashSet(r)
        case _ => applicable.map(_._1)
      }
    }
  }

  @tailrec
  private def coreType(tp: ScType): ScType = {
    tp match {
      case ScCompoundType(comps, _, _, subst) => ScCompoundType(comps, Seq.empty, Seq.empty, subst).removeVarianceAbstracts(1).removeUndefines()
      case ScExistentialType(quant, wilds) => ScExistentialType(quant.recursiveUpdate((tp: ScType) => {
          tp match {
            case ScTypeVariable(name) => wilds.find(_.name == name).map(w => (true, w.upperBound)).getOrElse((false, tp))
            case ScDesignatorType(element) => element match {
              case a: ScTypeAlias if a.getContext.isInstanceOf[ScExistentialClause] =>
                wilds.find(_.name == a.name).map(w => (true, w.upperBound)).getOrElse((false, tp))
              case _ => (false, tp)
            }
            case _ => (false, tp)
          }
        }), wilds).removeVarianceAbstracts(1).removeUndefines()
      case _ =>
        Conformance.isAliasType(tp) match {
          case Some(AliasType(_, lower, upper)) => coreType(upper.getOrAny)
          case _ => tp.removeVarianceAbstracts(1).removeUndefines()
        }
    }
  }

  private def dominates(t: ScType, u: ScType): Boolean = {
//    println(t, u, "T complexity: ", complexity(t), "U complexity: ", complexity(u), "t set: ", topLevelTypeConstructors(t),
//      "u set", topLevelTypeConstructors(u), "intersection: ", topLevelTypeConstructors(t).intersect(topLevelTypeConstructors(u)))
    complexity(t) > complexity(u) && topLevelTypeConstructors(t).intersect(topLevelTypeConstructors(u)).nonEmpty
  }

  private def topLevelTypeConstructors(tp: ScType): Set[ScType] = {
    tp match {
      case ScProjectionType(_, element, _, _) => Set(ScDesignatorType(element))
      case ScParameterizedType(designator, _) => Set(designator)
      case ScDesignatorType(v: ScValue) =>
        val valueType: ScType = v.getType(TypingContext.empty).getOrAny
        topLevelTypeConstructors(valueType)
      case ScCompoundType(comps, _, _, _) => comps.flatMap(topLevelTypeConstructors(_)).toSet
      case tp => Set(tp)
    }
  }

  private def complexity(tp: ScType): Int = {
    tp match {
      case ScProjectionType(proj, _, _, _) => 1 + complexity(proj)
      case ScParameterizedType(des, args) => 1 + args.foldLeft(0)(_ + complexity(_))
      case ScDesignatorType(v: ScValue) =>
        val valueType: ScType = v.getType(TypingContext.empty).getOrAny
        1 + complexity(valueType)
      case ScCompoundType(comps, _, _, _) => comps.foldLeft(0)(_ + complexity(_))
      case _ => 1
    }
  }
}