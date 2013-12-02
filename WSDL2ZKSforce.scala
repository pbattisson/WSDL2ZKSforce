// Copyright (c) 2013 Simon Fell
//
// Permission is hereby granted, free of charge, to any person obtaining a 
// copy of this software and associated documentation files (the "Software"), 
// to deal in the Software without restriction, including without limitation
// the rights to use, copy, modify, merge, publish, distribute, sublicense, 
// and/or sell copies of the Software, and to permit persons to whom the 
// Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included 
// in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS 
// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN 
// THE SOFTWARE.
//

// This is not a general purpose WSDL2Objc generator, its for generating
// classes for inclusion with ZKSforce when a new WSDL version comes out
// If you want to use ZKSforce you DO NOT HAVE TO RUN THIS, the relevant
// classes are already in ZKSforce.

import scala.xml._
import java.io._

// Helper class for generating a new source code file.
class SourceWriter(val file: File) {
	val w = new PrintWriter(file)

	def close() {
		w.close()
	}
	
	def println() {
		w.println()
	}
	
	def println(s: String) {
		w.println(s)
	}
	
	// for the collection of types, adds any import statements that would be needed.
	def printImports(types: Iterable[TypeInfo]) {
		val imports = types.map({
			_ match {
				case a:ArrayTypeInfo => if (a.componentType.isGeneratedType) a.componentType.objcName + ".h" else ""
				case t:TypeInfo      => if (t.isGeneratedType) t.objcName + ".h" else ""
			}
		})
		val ts = collection.immutable.TreeSet.empty[String]
		val its = ts ++ imports
		for (t <- its.filter(_.length > 0))
			printImport(t)
	}
	
	def printImport(f: String) {
		w.println(s"""#import "$f"""")
	}
	
	def printClassForwardDecl(c: String) {
		w.println(s"@class $c;")
	}
	
	// reads the first line of the copyright from the current matching source file in the zkSforce tree, if it exists.
	private def getOriginalCopyright(file: File): String = {
		val src = new File(file.getParentFile(), "../../zkSforce/zkSforce/" + file.getName())
		if (!src.exists()) return null
		val line = scala.io.Source.fromFile(src.getAbsolutePath()).getLines().next
		if (line contains "Copyright") line else null
	}
	
	def printLicenseComment() {
		val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
		val defaultCopyright = s"// Copyright (c) $year Simon Fell"
		val originalCopyright = getOriginalCopyright(file)
		val copyRight = if (originalCopyright == null) defaultCopyright else originalCopyright
		w.println(copyRight)
		w.println("""///
		/// Permission is hereby granted, free of charge, to any person obtaining a 
		/// copy of this software and associated documentation files (the "Software"), 
		/// to deal in the Software without restriction, including without limitation
		/// the rights to use, copy, modify, merge, publish, distribute, sublicense, 
		/// and/or sell copies of the Software, and to permit persons to whom the 
		/// Software is furnished to do so, subject to the following conditions:
		///
		/// The above copyright notice and this permission notice shall be included 
		/// in all copies or substantial portions of the Software.
		///
		/// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS 
		/// OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
		/// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
		/// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
		/// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, 
		/// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN 
		/// THE SOFTWARE.
		///
		/""".stripMargin('/'));
	}
}

// Each mapping from an XML based Type to an Objective-C type we need to generate is represented by a TypeInfo
// TypeInfo has a heirarchy for different types of types (e.g. arrays, classes, etc)
class TypeInfo(val xmlName: String, val objcName: String, accessor: String, val isPointer: Boolean) {
	
	// additional comment that'll get added to a property declaration of this type.
	def propertyDeclComment(): String =  { "" }
	
	// what property flags should be used for this type.
	def propertyFlags(): String = { if (isPointer) "retain" else "assign" }
	
	// full name of the objective-c type, includes * for pointer types.
	def fullTypeName(): String = { if (isPointer) objcName + " *" else objcName }
	
	// returns true if this type is one we generated rather than a system type
	def isGeneratedType(): Boolean = { objcName.startsWith("ZK") }	// TODO
	
	// returns an accessor that returns an instance of this type (when deserializing xml)
	def accessor(instanceName: String, elemName: String): String = {
		s"""[$instanceName $accessor:@"$elemName"]"""
	}
	
	// returns the name of the method that is used to serialize an instance of this type
	def serializerMethodName() : String = { 
		if (objcName == "BOOL") 			"addBoolElement" 
		else if (objcName == "NSInteger") 	"addIntElement"
		else 								"addElement"
	}
}

// For types that are mapped to Arrays.
class ArrayTypeInfo(val componentType: TypeInfo) extends TypeInfo(componentType.xmlName, "NSArray", "", true) {

	override def propertyDeclComment(): String =  { " // of " + componentType.objcName }
	
	override def accessor(instanceName:String, elemName: String): String = {
		if (componentType.objcName == "NSString")
			s"""[$instanceName strings:@"$elemName"]"""
		else
			s"""[$instanceName complexTypeArrayFromElements:@"$elemName" cls:[${componentType.objcName} class]]"""
	}
	
	override def serializerMethodName(): String = { 
		"addElementArray"
	}
}

val reservedWords = Set("inline")

// A property of a complexType (aka Class)
class ComplexTypeProperty(name: String, val propType: TypeInfo, val nillable: Boolean, val optional:Boolean) {
	
	val elementName = name
	val propertyName = makePropertyName(name)

	private def makePropertyName(name:String) : String = {
		if (reservedWords.contains(name)) "_" + name else name
	}
	
	def readImplBody(): String =  {
		s"""-(${propType.fullTypeName})$propertyName {
			|    return ${propType.accessor("self", elementName)};
			|}
			""".stripMargin('|')
	}
	
	def propertyDecl(padTypeTo: Int, readOnly: Boolean): String = {
		val f = if (readOnly) "readonly" else propType.propertyFlags
		val comment = propType.propertyDeclComment
		val td = typeDef(padTypeTo)
		s"@property ($f) $td; $comment"
	}
	
	def ivarDecl(padTypeTo: Int): String = {
		val td = typeDef(padTypeTo)
		s"\t$td;"
	}
	
	def parameterDecl(): String = {
		s"$propertyName:(${propType.fullTypeName})$propertyName"
	}
	
	private def typeDef(padTypeTo: Int): String = {
		val t = propType.objcName.padTo(padTypeTo - (if (propType.isPointer) 1 else 0), ' ')
		val p = if (propType.isPointer) "*" else ""
		s"$t$p$propertyName"
	}
	
	override def equals(other: Any): Boolean = {
		if (!other.isInstanceOf[ComplexTypeProperty]) return false
		val r = other.asInstanceOf[ComplexTypeProperty]
		r.propertyName == propertyName && r.propType.objcName == propType.objcName
	}
	
	// return the length of the serializer method name + the length of the element name, this is used to calc the right padding for a set of properties
	def serializerLength():Integer = {
		return propType.serializerMethodName.length + elementName.length + 1
	}
	
	private def objcBool(v: Boolean): String = {
		if (v) "YES" else "NO"
	}
	
	def serializerMethod(instName: String, padTo:Integer, padNameTo:Integer, valueScope:String) : String = {
		val addMethod = propType.serializerMethodName
		val pad = " ".padTo(padTo - addMethod.length - elementName.length, ' ')
		val scope = if (valueScope.length > 0) valueScope + "." else ""
		val namePad = "".padTo(padNameTo - elementName.length, ' ')
		val extra = if (propType.serializerMethodName == "addElement") 
						s"${namePad} nillable:${objcBool(nillable).padTo(3,' ')} optional:${objcBool(optional)}"
					else
						""
		s"""\t[$instName ${propType.serializerMethodName}:@"$elementName"${pad}elemValue:$scope$propertyName$extra];"""
	}
}

object Direction extends Enumeration {
	val Serialize, Deserialize = Value
}

// For types that are mapped from ComplexTypes (aka Classes)
class ComplexTypeInfo(xmlName: String, objcName: String, xmlNode: Node, val fields: Seq[ComplexTypeProperty], baseType:TypeInfo) extends TypeInfo(xmlName, objcName, "", true) {
	
	val direction =  Direction.ValueSet.newBuilder

	private def validate() {
		val dir = direction.result()
		if (dir.contains(Direction.Deserialize) && dir.contains(Direction.Serialize))
			throw new RuntimeException("complexType with both serialization & deserialization not supported")
	}
	
	override def accessor(instanceName:String, elemName: String): String = {
		s"""[[$instanceName complexTypeArrayFromElements:@"$elemName" cls:[${objcName} class]] lastObject]"""
	}
	
	protected def headerImportFile(): String = { if (baseType == null) "" else baseType.objcName + ".h" }
	protected def baseClass(): String = { if (baseType == null) "" else baseType.objcName }
	protected def includeIVarDecl(): Boolean = { false }
	protected def fieldsAreReadOnly(): Boolean = { true }
	protected def implementNSCopying(): Boolean = { false }
	
	def writeHeaderFile() {
		validate()

		val hfile = new File(new File("output"), objcName + ".h")
		hfile.getParentFile().mkdirs()
		val h = new SourceWriter(hfile)
		h.printLicenseComment()
		h.printImport(headerImportFile)
		h.println()
		writeForwardDecls(h)
		h.println("/*")
		val pp = new PrettyPrinter(809, 2)
		h.println(pp.format(xmlNode))
		h.println("*/")
		val nscopying = if (implementNSCopying()) "<NSCopying> " else ""
		h.println(s"@interface $objcName : ${baseClass} $nscopying{");
		writeHeaderIVars(h)
		h.println("}")
		writeHeaderProperties(h)
		h.println("@end")
		h.close()
	}

	protected def writeForwardDecls(w: SourceWriter) {
		for (f <- fields.filter(_.propType.isGeneratedType))
			w.printClassForwardDecl(f.propType.objcName)
	}
	
	private def padMembersTo(): Int = {
 		if (fields.length == 0) 0 else fields.map(_.propType.fullTypeName.length).max + 1
	}
	
	protected def writeHeaderIVars(w: SourceWriter) {
		if (includeIVarDecl)
			for (f <- fields)
				w.println(f.ivarDecl(padMembersTo))
	}

	protected def writeHeaderProperties(w: SourceWriter) {
		val padTo = padMembersTo
		for (f <- fields)
			w.println(f.propertyDecl(padTo, fieldsAreReadOnly))
	}
	
	protected def writeImplFileBody(w: SourceWriter) {} 
	
	protected def writeImplImports(w: SourceWriter) {}
		
	def writeImplFile() {
		val ifile = new File(new File("output"), objcName + ".m")
		val w = new SourceWriter(ifile)
		w.printLicenseComment()
		w.printImport(objcName + ".h")
		writeImplImports(w)
		w.println()
		w.println(s"@implementation $objcName")
		w.println()
		writeImplFileBody(w)
		w.println("@end")
		w.close()
	}
}

def writeFieldSerializers(instName:String, valueScope:String, w:SourceWriter, fields:Seq[ComplexTypeProperty]) {
	if (fields.length == 0) return;
	val padTo = fields.map(_.serializerLength).max
	val padNamesTo = fields.map(_.elementName.length).max
	for (f <- fields)
		w.println(f.serializerMethod(instName, padTo, padNamesTo, valueScope))
}

// A ComplexType from a message input, i.e. something we'll need to be able to serialize
class InputComplexTypeInfo(xmlName: String, objcName: String, xmlNode: Node, fields: Seq[ComplexTypeProperty], baseType:TypeInfo) extends ComplexTypeInfo(xmlName, objcName, xmlNode, fields, baseType) {

	override def headerImportFile(): String = { 
		val s = super.headerImportFile
		if (s == "") "ZKXMLSerializable.h" else s
	}
	
	override def baseClass(): String = { 
		val s = super.baseClass
		if (s == "") "NSObject<ZKXMLSerializable>" else s
	}
	
	override def includeIVarDecl(): Boolean = { true }
	override def fieldsAreReadOnly(): Boolean = { false }
	
	override protected def writeImplImports(w: SourceWriter) {
		w.printImport("zkEnvelope.h")
	}

	override protected def writeImplFileBody(w: SourceWriter) {
		w.println("@synthesize " + fields.map(_.propertyName).mkString(", ") + ";")
		w.println()
		w.println("-(void)dealloc {")
		for (f <- fields.filter(_.propType.isPointer))
			w.println(s"\t[${f.propertyName} release];")
		w.println("\t[super dealloc];")
		w.println("}")
		w.println()
		w.println("-(void)serializeToEnvelope:(ZKEnvelope *)env elemName:(NSString *)elemName {")
		w.println("\t[env startElement:elemName];")
		val fieldsToSerialize = if (baseType == null) fields else (baseType.asInstanceOf[ComplexTypeInfo].fields) ++ fields
		writeFieldSerializers("env", "self", w, fieldsToSerialize)
		w.println("\t[env endElement:elemName];")
		w.println("}")
	} 
}

// A ComplexType from a message output, i.e. something we'll need to be able to deserialize
class OutputComplexTypeInfo(xmlName: String, objcName: String, xmlNode: Node, fields: Seq[ComplexTypeProperty], baseType:TypeInfo) extends ComplexTypeInfo(xmlName, objcName, xmlNode, fields, baseType) {
	
	override def headerImportFile(): String = { 
		val s = super.headerImportFile
		if (s == "") "zkXmlDeserializer.h" else s
	}
	
	override def baseClass(): String = { 
		val s = super.baseClass
		if (s == "") "ZKXmlDeserializer" else s
	}
	
	override protected def writeImplImports(w: SourceWriter) {
		w.printImports(fields.map(_.propType))
	}
	
	protected def additionalNSCopyImpl(): String = { "" }
	 
	override protected def writeImplFileBody(w: SourceWriter) {
		if (implementNSCopying) {
			w.println(s"""-(id)copyWithZone:(NSZone *)zone {
				|    zkElement *e = [[node copyWithZone:zone] autorelease];
				|    $objcName *c = [[$objcName alloc] initWithXmlElement:e];
				|    ${additionalNSCopyImpl}
				|    return c;
				|}
				|
				|-(zkElement *)node {
				|	return node;
				|}
				|
				|-(BOOL)isEqual:(id)anObject {
				|	if (![anObject isKindOfClass:[$objcName class]]) return NO;
				|	return [node isEqual:[anObject node]];
				|}
				|
				|-(NSUInteger)hash {
				|	return [node hash];
				|}
				|""".stripMargin('|'))
		}
		for (f <- fields)
			w.println(f.readImplBody)
	}
}

class ZKDescribeField(xmlName:String, objcName:String, xmlNode:Node, fields:Seq[ComplexTypeProperty]) extends OutputComplexTypeInfo(xmlName, objcName, xmlNode, fields, null) {
	
	override protected def implementNSCopying(): Boolean = { true }
	
	override protected def writeForwardDecls(w: SourceWriter) {
		w.printClassForwardDecl("ZKDescribeSObject")
		super.writeForwardDecls(w)
	}
	
	override protected def writeHeaderIVars(w: SourceWriter) {
		w.println("\tZKDescribeSObject *sobject;")
	}
	
	override protected def writeHeaderProperties(w: SourceWriter) {
		w.println("@property (assign) ZKDescribeSObject *sobject; // assign to stop a ref counting loop")
		w.println()
		super.writeHeaderProperties(w)
	}
	
	override protected def writeImplImports(w: SourceWriter) {
		w.printImport("ZKDescribeSObject.h")
		super.writeImplImports(w)
	}
	
	override protected def additionalNSCopyImpl(): String = { "c.sobject = self.sobject;" }
	
	override protected def writeImplFileBody(w: SourceWriter) {
		w.println("@synthesize sobject;")
		w.println()
		super.writeImplFileBody(w)
	}
}

class ZKDescribeSObject(xmlName:String, objcName:String, xmlNode:Node, fields:Seq[ComplexTypeProperty]) extends OutputComplexTypeInfo(xmlName, objcName, xmlNode, fields, null) {

	override def headerImportFile(): String = { "ZKDescribeGlobalSObject.h" }
	override def baseClass(): String = { "ZKDescribeGlobalSObject" }
	
	override protected def writeHeaderIVars(w: SourceWriter) {
		w.println("	NSArray 	 *fieldList;")
		w.println("	NSDictionary *fieldsByName;")
	}

	override protected def writeImplFileBody(w: SourceWriter) {
		w.println("""-(void)dealloc {
					|	[fieldList release];
					|	[fieldsByName release];
					|	[super dealloc];
					|}
					|
					|-(NSArray *)fields {
					|	if (fieldList == nil) {
 					|		NSArray *fa = [self complexTypeArrayFromElements:@"fields" cls:[ZKDescribeField class]];
					|		for (ZKDescribeField *f in fa)
					|			[f setSobject:self];
					|		fieldList = [fa retain];
					|	}
					|	return fieldList;
					|}
					|""".stripMargin('|'));
					
		for (f <- fields.filter(_.propertyName != "fields"))
			w.println(f.readImplBody)
	}	
}

class VoidTypeInfo() extends TypeInfo("void", "void", "", false) {
	override def accessor(instanceName:String, elemName:String) : String = { "" }
}

class Operation(val name: String, val description: String, val params: Seq[ComplexTypeProperty], val returnType:TypeInfo, inputHeaders: Seq[String] ) {
	
	def objcSignature(): String = {
		s"-(${returnType.fullTypeName})$name${paramList}"
	}
	
	def writeMethodDecl(w: SourceWriter) {
		w.println(s"""// $description
					|$objcSignature;
					|""".stripMargin('|'))
	}
	
	def writeMethodImpl(w: SourceWriter) {
		val nullValue = if (returnType.objcName == "void") "" else "nil"
		w.println(s"""// $description
					|$objcSignature {
					|	if (!authSource) return $nullValue;
					|	[self checkSession];
					|	ZKEnvelope *env = [[[ZKPartnerEnvelope alloc] initWithSessionHeader:[authSource sessionId]] autorelease];""".stripMargin('|'))
		for (h <- inputHeaders.filter(_ != "SessionHeader"))
			w.println(s"	[self add${h}:env];")
		w.println(s"""|	[env moveToBody];
				    |	[env startElement:@"${name}"];""".stripMargin('|'));
		writeFieldSerializers("env", "", w, params)
		val retStmt = returnType.accessor("deser", "result")
		w.println(s"""	[env endElement:@"${name}"];""")
		if (returnType.objcName == "void") {
			w.println("""	[self sendRequest:[env end] name:NSStringFromSelector(_cmd)];
						|}
						|""".stripMargin('|'))
		} else {
			w.println(s"""	zkElement *rn = [self sendRequest:[env end] name:NSStringFromSelector(_cmd)];
					 |	ZKXmlDeserializer *deser = [[[ZKXmlDeserializer alloc] initWithXmlElement:rn] autorelease];
					 |	return $retStmt;
					 |}
					 |""".stripMargin('|'))
		}
	}
	
	def types():Seq[TypeInfo] = {
		returnType +: params.map(_.propType) 
	}
	
	def paramList():String = {
		if (params.length == 0) return ""
		val fp = params(0)
		val first = s":(${fp.propType.fullTypeName})${fp.propertyName}"
		if (params.length == 1) return first
		first + " " + params.tail.map(_.parameterDecl).mkString(" ")
	}
}

class BaseStubWriter(val allOperations: Seq[Operation]) {

	val operations = filterOps(allOperations);
	
	def filterOps(allOperations:Seq[Operation]) : Seq[Operation] = {
		return allOperations;
	}
	
	def writeClass() {
		writeHeader()
		writeImpl()
	}
	
	def referencedTypes(): Set[TypeInfo] = {
		Set(operations.map(_.types).flatten : _*)
	}
	
	def returnTypes(): Set[TypeInfo] = {
		val rt = Set(operations.map(_.returnType) : _*)
		val ordering = Ordering.fromLessThan[TypeInfo](_.objcName > _.objcName)
		val ts = collection.immutable.TreeSet.empty[TypeInfo](ordering)
		ts ++ rt
	}
	
	def writeHeader() {
		
	}
	
	def writeImpl() {
		
	}
}

class SyncStubWriter(allOperations: Seq[Operation]) extends BaseStubWriter(allOperations) {

	override def filterOps(allOperations:Seq[Operation]) : Seq[Operation] = {
		val toSkip = Set("login", "describeSObject", "create", "update", "describeGlobal", "search", "retreive")
		allOperations.filter(x => !toSkip.contains(x.name))
	}

	override def writeHeader() {
		val w= new SourceWriter(new File(new File("output"), "ZKSforceClient+Operations.h"))
		w.printLicenseComment()
		w.printImport("zkSforce.h")
		w.println()
		val rts = collection.immutable.TreeSet.empty[String] ++ referencedTypes.filter(_.isGeneratedType).map(_.objcName)
		for (t <- rts)
			w.printClassForwardDecl(t)
		w.println()
		w.println("@interface ZKSforceClient (Operations)")
		for (op <- operations)
			op.writeMethodDecl(w)
		w.println("@end")
		w.close()
	}
	
	override def writeImpl() {
		val w = new SourceWriter(new File(new File("output"), "ZKSforceClient+Operations.m"))
		w.printLicenseComment();
		w.printImport("ZKSforceClient+Operations.h")
		w.printImport("ZKPartnerEnvelope.h")
		w.printImports(referencedTypes)
		w.println()
		w.println("@implementation ZKSforceClient (Operations)")
		for (op <- operations)
			op.writeMethodImpl(w)
		w.println("@end")
		w.close()
	}
}

class ASyncStubWriter(allOperations: Seq[Operation]) extends BaseStubWriter(allOperations) {

	override def writeHeader() {
		val w = new SourceWriter(new File(new File("output"), "ZKSforceClient+zkAsyncQuery.h"))
		w.printLicenseComment()
		w.printImport("zkSforceClient.h")
		w.println()
		w.println("@interface ZKSforceClient (zkAsyncQuery)")
		w.println()
		val pad = returnTypes.map(blockType(_).length).max
		for (rt <- returnTypes.filter(_.objcName != "void"))
			printBlockTypeDef(w, blockType(rt), rt.fullTypeName, pad)
		
		printBlockTypeDef(w, "zkFailWithExceptionBlock", "NSException *", pad)
		printBlockTypeDef(w, "zkCompleteVoidBlock", "void", pad)
		w.println();
		for (op <- operations) {
			val cp = op.name.length + 15
			w.println(s"""// ${op.description}
						|-(void) perform${op.name.capitalize}${op.paramList}
						|${" ".padTo(cp-9,' ')}failBlock:(zkFailWithExceptionBlock)failBlock
						|${" ".padTo(cp-13,' ')}completeBlock:(${blockType(op.returnType)})completeBlock;
						|""".stripMargin('|'))
		}
		
		w.println("@end")
		w.close()
	}
	
	def printBlockTypeDef(w:SourceWriter, name:String, returnType:String, pad:Integer) {
		val padding = " ".padTo(pad - name.length + 1, ' ')
		val rt = if (returnType == "void") returnType else returnType+"result"
		w.println(s"typedef void (^$name)$padding(${rt});")
	}
	
	def blockType(t:TypeInfo) : String = {
		return "zkComplete" + t.objcName.substring(2) + "Block"
	}
	
	override def writeImpl() {
		
	}
}

class Schema(wsdl: Elem, typeMapping: Map[String, TypeInfo]) {
	private val complexTypeElems = createComplexTypesElems(wsdl)
	private val complexTypes = collection.mutable.Map[String, ComplexTypeInfo]()
	private val elements = createElements(wsdl)
	private val simpleTypes = createSimpleTypes(wsdl)
	private val bindingOperations = createBindingOperations(wsdl)
	private val operations = collection.mutable.MutableList[Operation]()
	private val headerNames = collection.mutable.MutableList[String]()
	private val VOID = new VoidTypeInfo()
	
	val messages = createMessages(wsdl)

	def addOperation(name: String, inputElemName: String, outputElemName: String, description: String) {
		val input = handleInputElement(inputElemName)
		val output = handleOutputElement(outputElemName)
		val opType = if (output.length > 0) output(0).propType else VOID
		// headers
		val bindingOp = bindingOperations(inputElemName)
		val headers = (bindingOp \ "input" \ "header").map(x => (
			handleHeader(stripPrefix((x \ "@message").text), (x \ "@part").text)))

		operations += new Operation(name, description, input, opType, headers)
	}
	
	def handleHeader(message:String, partName: String) : String = {
		val msg = messages(message)
		for (part <- msg \ "part") {
			if ((part \ "@name").text == partName) {
				val elmName = stripPrefix((part \ "@element").text)
				val elm = elements(elmName)
				if (!complexTypes.contains(elmName)) {
					makeComplexType(elmName, (elm \ "complexType")(0), Direction.Serialize)
					headerNames += elmName
				}
				return elmName
			}
		}
		return null
	}
	
	def handleInputElement(elementName: String): Seq[ComplexTypeProperty] = {
		return handleElement(elementName, Direction.Serialize)
	}
	
	def handleOutputElement(elementName: String): Seq[ComplexTypeProperty] = {
		return handleElement(elementName, Direction.Deserialize)
	}
	
	private def handleElement(elementName: String, dir: Direction.Value): Seq[ComplexTypeProperty] = {
		// walk through the element decl and build the related types.
		val element = elements(elementName)
		return (element \ "complexType" \ "sequence" \ "element").map(generateField(_, dir))
	}
	
	// some types are extensions of base types, and not found by the basic operation driven traversal
	// so we need to go through and find these. (alternatively we could just create types for all complexTypes
	// in the wsdl, think about doing that instead)
	def addDerivedTypes() {
		for (ct <- complexTypeElems.values) {
			val name = (ct \ "@name").text
			if (!complexTypes.contains(name)) {
				val rawBaseName = (ct \ "complexContent" \ "extension" \ "@base").text
				if (rawBaseName != null) {
					val baseName = stripPrefix(rawBaseName)
					if (complexTypes contains baseName) {
						val baseType = complexTypes(baseName)
						makeComplexType(name, baseType.direction.result.firstKey)
					}
				}	
			}
		}
	}
	
	def complexType(xmlName: String, dir: Direction.Value) : ComplexTypeInfo = {
		val t = complexTypes.getOrElse(xmlName, makeComplexType(xmlName, dir))
		t.direction += dir
		return t
	}
	
	def writeClientStub() {
		new SyncStubWriter(operations).writeClass()
		new ASyncStubWriter(operations).writeClass()
	}
	
	def writeTypes() {
		for ((_, ct) <- complexTypes) {
			ct.writeHeaderFile()
			ct.writeImplFile()
		}
		for (h <- headerNames)
			println("Header " + h)
	}
	
    def writeZKSforceh() {
		val w = new SourceWriter(new File(new File("output"), "zkSforce.h"))
		w.printLicenseComment()
		val fixedImports = List("zkSforceClient.h", "zkSObject.h", "zkSoapException.h")
		for (i <- fixedImports)
			w.printImport(i)
		w.printImports(complexTypes.values)
		for(f <- new File("../zkSforce/zkSforce").listFiles().filter(_.getName().contains("+")).filter(_.getName().endsWith(".h")))
			w.printImport(f.getName())
		w.close()
	}
	
	private def createBindingOperations(wsdl: Elem): Map[String, Node] = {
		(wsdl \ "binding" \ "operation").map(x => ((x \ "@name").text, x)).toMap
	}
	
	private def createSimpleTypes(wsdl: Elem): Map[String, Node] = {
		val schemas = (wsdl \ "types" \ "schema" )
		for (schema <- schemas) {
			if ((schema \ "@targetNamespace").text == "urn:partner.soap.sforce.com") {
				return (schema \ "simpleType").map( x => ( (x \ "@name").text, x )).toMap
			}
		}
		return collection.immutable.Map[String, Node]()
	}
	
	private def createElements(wsdl: Elem): Map[String, Node] = {
		val schemas = (wsdl \ "types" \ "schema" )
		for (schema <- schemas) {
			if ((schema \ "@targetNamespace").text == "urn:partner.soap.sforce.com") {
				return (schema \ "element").map( x => ( (x \ "@name").text, x )).toMap
			}
		}
		return collection.immutable.Map[String, Node]()
	}
	
	private def createMessages(wsdl: Elem): Map[String, Node] = {
		(wsdl \ "message").map( x => ((x \ "@name").text, x)).toMap
	}
	
	private def createComplexTypesElems(wsdl: Elem): Map[String, Node] = {
		val schemas = (wsdl \ "types" \ "schema" )
		for (schema <- schemas) {
			if ((schema \ "@targetNamespace").text == "urn:partner.soap.sforce.com") {
				return (schema \ "complexType").map( x => ( (x \ "@name").text, x )).toMap
			}
		}
		return collection.immutable.Map[String, Node]()
	}
	
	
	private def makeObjcName(xmlName: String): String = {
		// There are some generated types that for legacy reasons we want to have a different name to the default name mapped from the wsdl
		val newNames = Map(
						// default Name		  			-> name to use instead
						"GetUserInfoResult" 			-> "ZKUserInfo",
						"Field"	  		    			-> "ZKDescribeField",
						"DescribeGlobalSObjectResult" 	-> "ZKDescribeGlobalSObject",
						"DescribeSObjectResult"			-> "ZKDescribeSObject"
						)
		return newNames.getOrElse(xmlName, "ZK" + xmlName.capitalize)
	}

	private def defaultComplexType(dir: Direction.Value, xmlName: String, objcName: String, ct: Node, fields: Seq[ComplexTypeProperty], baseType: TypeInfo): ComplexTypeInfo = {
		if (objcName == "ZKDescribeField")
			new ZKDescribeField(xmlName, objcName, ct, fields)

		else if (objcName == "ZKDescribeSObject") {
			val dg = complexType("DescribeGlobalSObjectResult", Direction.Deserialize);
			val childFields = fields.filter(!dg.fields.contains(_))
			new ZKDescribeSObject(xmlName, objcName, ct, childFields)
		}
		
		else if (dir == Direction.Serialize)
			new InputComplexTypeInfo(xmlName, objcName, ct, fields, baseType)

		else
			new OutputComplexTypeInfo(xmlName, objcName, ct, fields, baseType)
	}
	
	private def makeComplexType(xmlName: String, dir: Direction.Value): ComplexTypeInfo = {
		val ct = complexTypeElems(xmlName)
		makeComplexType(xmlName, ct, dir)
	}
	
	private def makeComplexType(xmlName: String, complexTypeNode: Node, dir: Direction.Value): ComplexTypeInfo = {
		val objcName = makeObjcName(xmlName)
		// we insert a temporary version of the complexType to handle recursive definitions
		complexTypes(xmlName) = new ComplexTypeInfo(xmlName, objcName, complexTypeNode, List(), null)
		val base:String = stripPrefix((complexTypeNode \ "complexContent" \ "extension" \ "@base").text)
		val baseType:TypeInfo = if (base.length > 0) complexType(base, dir) else null
		val sequence = if (base.length > 0) (complexTypeNode \ "complexContent" \ "extension" \ "sequence") else (complexTypeNode \ "sequence")
		val fields = (sequence \ "element").map( x => generateField(x, dir) )
		val i = defaultComplexType(dir, xmlName, objcName, complexTypeNode, fields, baseType)
		i.direction += dir
		complexTypes(xmlName) = i
		return i
	}

	private def generateField(field: Node, dir: Direction.Value): ComplexTypeProperty = {
		val max = (field \ "@maxOccurs").text
		val array = (max != "" && max != "1")
		val xmlt = elementType(field)
		val name = (field \ "@name").text
		val singleType = getType(xmlt, dir)
		val t = if (array) new ArrayTypeInfo(singleType) else singleType
		val optional = (field \ "@minOccurs").text == "0"
		val nillable = (field \ "@nillable").text == "true"
		new ComplexTypeProperty(name, t, nillable, optional)
	}

	private def getType(xmlName: String, dir: Direction.Value): TypeInfo = {
		if (typeMapping contains xmlName) return typeMapping(xmlName)
		if (complexTypes contains xmlName) return complexType(xmlName, dir)
		if (simpleTypes contains xmlName) return typeMapping("string")	// are all simple types string extentions/restrictions ?
		makeComplexType(xmlName, dir)	// no where else, assume its a complexType we haven't processed yet
	}
	
	private def elementType(e: Node): String = {
		stripPrefix((e \ "@type").text)
	}
}

def stripPrefix(v: String): String = {
	val c = v.indexOf(':')
	if (c == -1) v else v.substring(c+1)
}

object WSDL2ZKSforce {
	def main(args: Array[String]) {
		val types = Map(
					"string" 		-> new TypeInfo("string", 		"NSString",  	"string",  		true),
					"int" 	 		-> new TypeInfo("int",    		"NSInteger", 	"integer", 		false),
					"boolean"		-> new TypeInfo("boolean", 		"BOOL", 	 	"boolean", 		false),
					"ID"	 		-> new TypeInfo("ID",			"NSString",  	"string",  		true),
					"sObject"		-> new TypeInfo("sObject", 		"ZKSObject", 	"sObject",  	true),
					"QueryResult"   -> new TypeInfo("QueryResult",  "ZKQueryResult","queryResult",	true),
					"dateTime"		-> new TypeInfo("dateTime",		"NSDate",  	 	"dateTime", 	true),
					"date"   		-> new TypeInfo("date",    		"NSDate",    	"date",     	true),
					"base64Binary" 	-> new TypeInfo("base64Binary", "NSData", 	 	"blob",     	true)
					)
					
		val wsdl = XML.loadFile("./partner.wsdl")
		val schema = new Schema(wsdl, types)
		
		for (op <- (wsdl \ "portType" \ "operation")) {
			val opName = (op \ "@name").text
			val inMsg  = schema.messages(stripPrefix((op \ "input" \ "@message").text))
			val outMsg = schema.messages(stripPrefix((op \ "output" \ "@message").text))
			val inElm  = stripPrefix((inMsg \ "part" \ "@element").text)
			val outElm = stripPrefix((outMsg \ "part" \ "@element").text)
			val desc   = (op \ "documentation").text
			println(opName.padTo(40, ' ') + inElm.padTo(40, ' ' ) + outElm)
			schema.addOperation(opName, inElm, outElm, desc)
		}

		schema.addDerivedTypes()
		schema.writeClientStub()
		schema.writeTypes()
		schema.writeZKSforceh()
  	}
}

WSDL2ZKSforce.main(args)
