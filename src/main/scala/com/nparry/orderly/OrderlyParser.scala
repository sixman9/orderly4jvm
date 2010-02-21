/*
 *  Copyright (c) 2010, Nathan Parry
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are
 *  met:
 * 
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 * 
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in
 *     the documentation and/or other materials provided with the
 *     distribution.
 * 
 *  3. Neither the name of Nathan Parry nor the names of any
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 * 
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
 *  INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 *  SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 *  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 *  STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 *  IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *  POSSIBILITY OF SUCH DAMAGE.
 */
package com.nparry.orderly

import scala.util.parsing.combinator._
import scala.util.parsing.combinator.syntactical._
import scala.util.parsing.combinator.lexical._
import scala.util.parsing.input.{Reader,CharArrayReader}

import net.liftweb.json.JsonAST._
import net.liftweb.json.Implicits._

/**
 * An implementation of Orderly JSON (http://orderly-json.org/).
 * This parser produces a JSON object representing the JSON
 * schema of the given Orderly input.
 *
 * This is based on the Orderly grammar at:
 * http://github.com/lloyd/orderly/blob/master/docs.md
 */
object OrderlyParser extends JavaTokenParsers {

  // Some helpers to shorten the code below

  def t(name: String) = f("type", name)
  def f(k: String, v: JValue) = JField(k, v)
  def l [A] (x: Option[List[A]]): List[A] = x getOrElse List()


  // The orderly grammar

  def orderlySchema: Parser[JObject] = unnamedEntry <~ ";"
  def namedEntries: Parser[JObject]   = repsep(namedEntry, ";") ^^ (JObject(_))
  def unnamedEntries: Parser[JArray] = repsep(unnamedEntry, ";") ^^ (JArray(_))
  def namedEntry: Parser[JField] =
    (definitionPrefix ~ propertyName ~ definitionSuffix) ^^ 
      { case p ~ n ~ s => f(n.values, JObject(p ++ s)) } |
    (stringPrefix ~ propertyName ~ stringSuffix) ^^ 
      { case p ~ n ~ s => f(n.values, JObject(p ++ s)) }
  def unnamedEntry: Parser[JObject] =
    (definitionPrefix ~ definitionSuffix) ^^ { case p ~ s => JObject(p ++ s) } |
    (stringPrefix ~ stringSuffix) ^^ { case p ~ s => JObject(p ++ s) }
  def definitionPrefix: Parser[List[JField]] =
    "boolean" ^^^ (List(t("boolean"))) | 
    "null"    ^^^ (List(t("null"))) |
    "any"     ^^^ (List(t("any"))) |
    ("integer" ~> opt(range("minimum", "maximum"))) ^^ { r => t("integer") :: l(r) } |
    ("number"  ~> opt(range("minimum", "maximum"))) ^^ { r => t("number") :: l(r) } |
    ("array" ~> "{" ~> unnamedEntries <~ "}") ~ opt(additionalMarker) ~ opt(range("minItems", "maxItems")) ^^
      { case e ~ m ~ r =>  t("array") :: f("items", e) :: (l(r) ++ l(m)) } |
    ("array" ~> "[" ~> unnamedEntry <~ "]") ~ opt(range("minItems", "maxItems")) ^^
      { case e ~ r => t("array") :: f("items", e) :: l(r) } |
    ("object" ~> "{" ~> namedEntries <~ "}") ~ opt(additionalMarker)  ^^
      { case e ~ m => t("object") :: f("properties", e) :: l(m) } 
    ("union" ~> "{" ~> unnamedEntries <~ "}") ^^ { case e => List(f("type", e)) }
  def stringPrefix: Parser[List[JField]] = "string" ~> opt(range("minLength", "maxLength")) ^^
    { case r => t("string") :: l(r) }
  def stringSuffix: Parser[List[JField]] = opt(perlRegex) ~ definitionSuffix ^^
    { case r ~ s => l(r) ++ s }
  def definitionSuffix: Parser[List[JField]] =
    opt(enumValues) ~ opt(defaultValue) ~ opt(rqires) ~ opt(optionalMarker) ~ opt(extraProperties) ^^
    { case e ~ d ~ r ~ m ~ x => l(e) ++ l(d) ++ l(r) ++ l(m) ++ l(x) }
  def extraProperties: Parser[List[JField]] = "`" ~> jsonObj <~ "`" ^^
    { case JObject(l) => l }
  def rqires: Parser[List[JField]] = "<" ~> repsep(propertyName, ",") <~ ">" ^^
    { n => List(f("requires", JArray(n))) }
  def optionalMarker: Parser[List[JField]] = "?" ^^^ List(f("optional", true))
  def additionalMarker: Parser[List[JField]] = "*" ^^^ List(f("additionalProperties", true))
  def enumValues: Parser[List[JField]] = jsonArray ^^
    { case a => List(f("enum", a)) }
  def defaultValue: Parser[List[JField]] = "=" ~> jsonValue ^^
    { case d => List(f("default", d)) }
  def range(l:String, h:String): Parser[List[JField]] =
    ("{" ~> floatingPointNumber ~ "," ~ floatingPointNumber <~ "}") ^^
      { case min ~ "," ~ max => List(f(l, min), f(h, max)) } |
    ("{" ~> floatingPointNumber <~ "," <~ "}")  ^^
      { case min => List(f(l, min)) } | 
    ("{" ~> "," ~> floatingPointNumber <~ "}") ^^
      { case max => List(f(h, max)) } |
    ("{" ~ "," ~ "}") ^^^ List()
  def propertyName: Parser[JString] =
    stringLiteral ^^ { JString(_) } |
    "[A-Za-z_-]+".r ^^ { JString(_) }
  def perlRegex: Parser[List[JField]] = ("/" ~> "[^/]".r <~ "/") ^^
    { case r => List(f("pattern", r)) }


  // Mini grammar to parse JSON

  def jsonObj: Parser[JObject] = "{" ~> repsep(jsonMember, ",") <~ "}" ^^ { JObject(_) }
  def jsonMember = stringLiteral ~ ":" ~ jsonValue ^^ { case k ~ ":" ~ v => f(k, v) }
  def jsonArray = "[" ~> repsep(jsonValue, ",") <~ "]" ^^ { JArray(_) }
  def jsonValue: Parser[JValue] =
    jsonObj |
    jsonArray |
    stringLiteral ^^ { JString(_) } |
    floatingPointNumber ^^ { x => JDouble(x.toDouble) } |
    "null" ^^^ JNull |
    "true" ^^^ JBool(true) |
    "false" ^^^ JBool(false)


  // Entry points

  def parseOrderlyString(s: String) = parseOrderly(new CharArrayReader(s.toCharArray()))
  def parseOrderly(input: Reader[Char]): JObject  =
    phrase(orderlySchema)(input) match {
      case Success(result, _) => result
      case _ => throw new Exception("Invalid Orderly")
    }
 
  /*
   * Orderly input to a pretty printed JSON schema output.
   * Mostly useful for interactive testing
   */
  def schemaAsString(s: String) = net.liftweb.json.Printer.pretty(render(parseOrderlyString(s)))
}
