/*
 * Copyright 2020 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.realm.compiler

import io.realm.compiler.FqNames.KOTLIN_COLLECTIONS_LISTOF
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.at
import org.jetbrains.kotlin.ir.builders.declarations.IrFieldBuilder
import org.jetbrains.kotlin.ir.builders.declarations.IrFunctionBuilder
import org.jetbrains.kotlin.ir.builders.declarations.IrPropertyBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrMutableAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperInterfaces

// Somehow addSetter was removed from the IrProperty in https://github.com/JetBrains/kotlin/commit/d1dc938a5d7331ba43fcbb8ce53c3e17ef76a22a#diff-2726c3747ace0a1c93ad82365cf3ff18L114
// Remove this extension when this will be re-introduced? see https://kotlinlang.slack.com/archives/C7L3JB43G/p1600888883006300
inline fun IrProperty.addSetter(builder: IrFunctionBuilder.() -> Unit = {}): IrSimpleFunction =
    IrFunctionBuilder().run {
        factory.buildFun {
            this.name = Name.special("<set-${this@addSetter.name}>")
            builder()
        }.also { setter ->
            this@addSetter.setter = setter
            setter.correspondingPropertySymbol = this@addSetter.symbol
            setter.parent = this@addSetter.parent
        }
    }

fun IrPluginContext.blockBody(
    symbol: IrSymbol,
    block: IrBlockBodyBuilder.() -> Unit
): IrBlockBody =
    DeclarationIrBuilder(this, symbol).irBlockBody { block() }

val ClassDescriptor.isRealmObjectCompanion
    get() = isCompanionObject && (containingDeclaration as ClassDescriptor).hasRealmModelInterface

val ClassDescriptor.hasRealmModelInterface
    get() = getSuperInterfaces().firstOrNull { it.fqNameSafe == FqNames.REALM_MODEL_INTERFACE } != null

fun IrMutableAnnotationContainer.hasAnnotation(annotation: FqName): Boolean {
    return annotations.hasAnnotation(annotation)
}

val IrMutableAnnotationContainer.isRealmModuleAnnotated
    get() = annotations.hasAnnotation(FqNames.REALM_MODULE_ANNOTATION)

val IrClass.hasRealmModelInterface
    get() = superTypes.firstOrNull {
        it.classFqName?.equals(FqNames.REALM_MODEL_INTERFACE) ?: false
    } != null

internal fun IrFunctionBuilder.at(startOffset: Int, endOffset: Int) = also {
    this.startOffset = startOffset
    this.endOffset = endOffset
}

internal fun IrFieldBuilder.at(startOffset: Int, endOffset: Int) = also {
    this.startOffset = startOffset
    this.endOffset = endOffset
}

internal fun IrPropertyBuilder.at(startOffset: Int, endOffset: Int) = also {
    this.startOffset = startOffset
    this.endOffset = endOffset
}

internal fun IrClass.lookupFunction(name: Name): IrSimpleFunction {
    return functions.firstOrNull { it.name == name }
        ?: throw AssertionError("Function '$name' not found in class '${this.name}'")
}

internal fun IrClass.lookupProperty(name: Name): IrProperty {
    return properties.firstOrNull { it.name == name }
        ?: throw AssertionError("Property '$name' not found in class '${this.name}'")
}

internal fun IrPluginContext.lookupFunctionInClass(
    fqName: FqName,
    function: String
): IrSimpleFunction {
    return lookupClassOrThrow(fqName).functions.first {
        it.name == Name.identifier(function)
    }
}

internal fun IrPluginContext.lookupClassOrThrow(name: FqName): IrClass {
    return referenceClass(name)?.owner
        ?: error("Cannot find ${name.asString()} on platform $platform.")
}

internal fun IrPluginContext.lookupConstructorInClass(
    fqName: FqName,
    filter: (ctor: IrConstructorSymbol) -> Boolean
): IrConstructorSymbol {
    return referenceConstructors(fqName).first {
        filter(it)
    }
}

object SchemaCollector {
    val properties = mutableMapOf<IrClass, MutableMap<String, Pair<String, IrProperty>>>()
}

@Suppress("LongParameterList")
internal fun <T : IrExpression> buildOf(
    context: IrPluginContext,
    startOffset: Int,
    endOffset: Int,
    function: IrSimpleFunctionSymbol,
    containerType: IrClass,
    elementType: IrType,
    args: List<T>
): IrExpression {
    return IrCallImpl(
        startOffset = startOffset, endOffset = endOffset,
        type = containerType.typeWith(elementType),
        symbol = function,
        typeArgumentsCount = 1,
        valueArgumentsCount = 1,
        origin = null,
        superQualifierSymbol = null
    ).apply {
        putTypeArgument(index = 0, type = elementType)
        putValueArgument(
            index = 0,
            valueArgument = IrVarargImpl(
                UNDEFINED_OFFSET,
                UNDEFINED_OFFSET,
                context.irBuiltIns.arrayClass.typeWith(elementType),
                type,
                args.toList()
            )
        )
    }
}

internal fun <T : IrExpression> buildSetOf(
    context: IrPluginContext,
    startOffset: Int,
    endOffset: Int,
    elementType: IrType,
    args: List<T>
): IrExpression {
    val setOf = context.referenceFunctions(FqName("kotlin.collections.setOf"))
        .first {
            val parameters = it.owner.valueParameters
            parameters.size == 1 && parameters.first().isVararg
        }
    val setIrClass: IrClass = context.lookupClassOrThrow(FqNames.KOTLIN_COLLECTIONS_SET)
    return buildOf(context, startOffset, endOffset, setOf, setIrClass, elementType, args)
}

internal fun <T : IrExpression> buildListOf(
    context: IrPluginContext,
    startOffset: Int,
    endOffset: Int,
    elementType: IrType,
    args: List<T>
): IrExpression {
    val listOf = context.referenceFunctions(KOTLIN_COLLECTIONS_LISTOF)
        .first {
            val parameters = it.owner.valueParameters
            parameters.size == 1 && parameters.first().isVararg
        }
    val listIrClass: IrClass = context.lookupClassOrThrow(FqNames.KOTLIN_COLLECTIONS_LIST)
    return buildOf(context, startOffset, endOffset, listOf, listIrClass, elementType, args)
}

fun IrClass.addValueProperty(
    pluginContext: IrPluginContext,
    superClass: IrClass,
    propertyName: Name,
    propertyType: IrType,
    initExpression: (startOffset: Int, endOffset: Int) -> IrExpression
): IrProperty {
    // PROPERTY name:realmPointer visibility:public modality:OPEN [var]
    val property = addProperty {
        at(this@addProperty.startOffset, this@addProperty.endOffset)
        name = propertyName
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        isVar = true
    }
    // FIELD PROPERTY_BACKING_FIELD name:objectPointer type:kotlin.Long? visibility:private
    property.backingField = pluginContext.irFactory.buildField {
        at(this@addValueProperty.startOffset, this@addValueProperty.endOffset)
        origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
        name = property.name
        visibility = DescriptorVisibilities.PRIVATE
        modality = property.modality
        type = propertyType
    }.apply {
        initializer = IrExpressionBodyImpl(initExpression(startOffset, endOffset))
    }
    property.backingField?.parent = this
    property.backingField?.correspondingPropertySymbol = property.symbol

    val getter = property.addGetter {
        at(this@addValueProperty.startOffset, this@addValueProperty.endOffset)
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        returnType = propertyType
        origin = IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR
    }
    // $this: VALUE_PARAMETER name:<this> type:dev.nhachicha.Foo.$RealmHandler
    getter.dispatchReceiverParameter = thisReceiver!!.copyTo(getter)
    // overridden:
    //   public abstract fun <get-realmPointer> (): kotlin.Long? declared in dev.nhachicha.RealmObjectInternal
    val propertyAccessorGetter = superClass.getPropertyGetter(propertyName.asString())
        ?: error("${propertyName.asString()} function getter symbol is not available")
    getter.overriddenSymbols = listOf(propertyAccessorGetter)

    // BLOCK_BODY
    // RETURN type=kotlin.Nothing from='public final fun <get-objectPointer> (): kotlin.Long? declared in dev.nhachicha.Foo.$RealmHandler'
    // GET_FIELD 'FIELD PROPERTY_BACKING_FIELD name:objectPointer type:kotlin.Long? visibility:private' type=kotlin.Long? origin=null
    // receiver: GET_VAR '<this>: dev.nhachicha.Foo.$RealmHandler declared in dev.nhachicha.Foo.$RealmHandler.<get-objectPointer>' type=dev.nhachicha.Foo.$RealmHandler origin=null
    getter.body = pluginContext.blockBody(getter.symbol) {
        at(startOffset, endOffset)
        +irReturn(
            irGetField(irGet(getter.dispatchReceiverParameter!!), property.backingField!!)
        )
    }
    return property
}
