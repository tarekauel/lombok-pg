/*
 * Copyright © 2011 Philipp Eichhorn
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.core.handlers;

import static lombok.ast.AST.*;
import static lombok.ast.IMethod.ArgumentStyle.INCLUDE_ANNOTATIONS;
import static lombok.ast.Wildcard.Bound.EXTENDS;
import static lombok.core.util.Names.*;

import java.util.*;

import lombok.*;
import lombok.ast.*;

public abstract class BuilderAndExtensionHandler<TYPE_TYPE extends IType<METHOD_TYPE, ?, ?, ?, ?, ?>, METHOD_TYPE extends IMethod<TYPE_TYPE, ?, ?, ?>, FIELD_TYPE extends IField<?, ?, ?>> {
	public static final String OPTIONAL_DEF = "$OptionalDef";
	public static final String BUILDER = "$Builder";

	public void handleBuilder(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData) {
		final TYPE_TYPE type = builderData.getType();
		final List<TypeRef> requiredFieldDefTypes = builderData.getRequiredFieldDefTypes();
		final List<TypeRef> interfaceTypes = new ArrayList<TypeRef>(requiredFieldDefTypes);
		interfaceTypes.add(Type(OPTIONAL_DEF));
		for (TypeRef interfaceType : interfaceTypes) interfaceType.withTypeArguments(type.typeArguments());
		final List<AbstractMethodDecl<?>> builderMethods = new ArrayList<AbstractMethodDecl<?>>();
		final TypeRef fieldDefType = builderData.getRequiredFields().isEmpty() ? Type(OPTIONAL_DEF) : requiredFieldDefTypes.get(0);

		createConstructor(builderData);
		createInitializeBuilderMethod(builderData, fieldDefType);
		createRequiredFieldInterfaces(builderData, builderMethods);
		createOptionalFieldInterface(builderData, builderMethods);
		createBuilder(builderData, interfaceTypes, builderMethods);
	}

	public void handleExtension(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final METHOD_TYPE method, final IParameterValidator<METHOD_TYPE> validation,
			final IParameterSanitizer<METHOD_TYPE> sanitizer) {
		TYPE_TYPE type = builderData.getType();
		IExtensionCollector extensionCollector = builderData.getExtensionCollector().withRequiredFieldNames(builderData.getAllRequiredFieldNames());
		collectExtensions(method, extensionCollector);
		if (extensionCollector.isExtension()) {
			TYPE_TYPE builderType = type.<TYPE_TYPE>memberType(BUILDER);
			TYPE_TYPE interfaceType;
			if (extensionCollector.isRequiredFieldsExtension()) {
				interfaceType = type.<TYPE_TYPE>memberType(builderData.getRequiredFieldDefTypeNames().get(0));
			} else {
				interfaceType = type.<TYPE_TYPE>memberType(OPTIONAL_DEF);
			}
			builderType.injectMethod(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), method.name()).makePublic().implementing().withArguments(method.arguments(INCLUDE_ANNOTATIONS)) //
				.withStatements(validation.validateParameterOf(method)) //
				.withStatements(sanitizer.sanitizeParameterOf(method)) //
				.withStatements(method.statements()) //
				.withStatement(Return(This())));
			interfaceType.injectMethod(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), method.name()).makePublic().withNoBody().withArguments(method.arguments(INCLUDE_ANNOTATIONS)));
			type.removeMethod(method);
		}
	}

	private void createConstructor(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData) {
		TYPE_TYPE type = builderData.getType();
		ConstructorDecl constructorDecl = ConstructorDecl(type.name()).makePrivate().withArgument(Arg(Type(BUILDER).withTypeArguments(type.typeArguments()), "builder").makeFinal()).withImplicitSuper();
		for (final FIELD_TYPE field : builderData.getAllFields()) {
			constructorDecl.withStatement(Assign(Field(field.name()), Field(Name("builder"), field.name())));
		}
		type.injectConstructor(constructorDecl);
	}

	private void createInitializeBuilderMethod(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final TypeRef fieldDefType) {
		final TYPE_TYPE type = builderData.getType();
		type.injectMethod(MethodDecl(fieldDefType, decapitalize(type.name())).makeStatic().withAccessLevel(builderData.getLevel()).withTypeParameters(type.typeParameters()) //
			.withStatement(Return(New(Type(BUILDER).withTypeArguments(type.typeArguments())))));
	}

	private void createRequiredFieldInterfaces(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final List<AbstractMethodDecl<?>> builderMethods) {
		List<FIELD_TYPE> fields = builderData.getRequiredFields();
		if (!fields.isEmpty()) {
			TYPE_TYPE type = builderData.getType();
			List<String> names = builderData.getRequiredFieldDefTypeNames();
			FIELD_TYPE field = fields.get(0);
			String name = names.get(0);
			for (int i = 1, iend = fields.size(); i < iend; i++) {
				List<AbstractMethodDecl<?>> interfaceMethods = new ArrayList<AbstractMethodDecl<?>>();
				createFluentSetter(builderData, names.get(i), field, interfaceMethods, builderMethods);

				type.injectType(InterfaceDecl(name).makePublic().makeStatic().withMethods(interfaceMethods));
				field = fields.get(i);
				name = names.get(i);
			}
			List<AbstractMethodDecl<?>> interfaceMethods = new ArrayList<AbstractMethodDecl<?>>();
			createFluentSetter(builderData, OPTIONAL_DEF, field, interfaceMethods, builderMethods);

			type.injectType(InterfaceDecl(name).makePublic().makeStatic().withTypeParameters(type.typeParameters()).withMethods(interfaceMethods));
		}
	}

	private void createOptionalFieldInterface(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final List<AbstractMethodDecl<?>> builderMethods) {
		TYPE_TYPE type = builderData.getType();
		List<AbstractMethodDecl<?>> interfaceMethods = new ArrayList<AbstractMethodDecl<?>>();
		for (FIELD_TYPE field : builderData.getOptionalFields()) {
			if (isInitializedMapOrCollection(field)) {
				if (builderData.isGenerateConvenientMethodsEnabled()) {
					if (isCollection(field)) {
						createCollectionMethods(builderData, field, interfaceMethods, builderMethods);
					}  else if (isMap(field)) {
						createMapMethods(builderData, field, interfaceMethods, builderMethods);
					}
				}
			} else {
				createFluentSetter(builderData, OPTIONAL_DEF, field, interfaceMethods, builderMethods);
			}
		}

		createBuildMethod(builderData, type.name(), interfaceMethods, builderMethods);

		for (String callMethod : builderData.getCallMethods()) {
			createMethodCall(builderData, callMethod, interfaceMethods, builderMethods);
		}

		type.injectType(InterfaceDecl(OPTIONAL_DEF).makePublic().makeStatic().withTypeParameters(type.typeParameters()).withMethods(interfaceMethods));
	}

	private void createFluentSetter(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final String typeName, final FIELD_TYPE field,
			final List<AbstractMethodDecl<?>> interfaceMethods, final List<AbstractMethodDecl<?>> builderMethods) {
		TYPE_TYPE type = builderData.getType();
		String methodName = camelCase(builderData.getPrefix(), field.name());
		final Argument arg0 = Arg(field.type(), field.name()).makeFinal();
		builderMethods.add(MethodDecl(Type(typeName).withTypeArguments(type.typeArguments()), methodName).makePublic().implementing().withArgument(arg0) //
			.withStatement(Assign(Field(field.name()), Name(field.name()))) //
			.withStatement(Return(This())));
		interfaceMethods.add(MethodDecl(Type(typeName).withTypeArguments(type.typeArguments()), methodName).makePublic().withNoBody().withArgument(arg0));
	}

	private void createCollectionMethods(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final FIELD_TYPE field,
			final List<AbstractMethodDecl<?>> interfaceMethods, final List<AbstractMethodDecl<?>> builderMethods) {
		TYPE_TYPE type = builderData.getType();
		TypeRef elementType = Type(Object.class);
		TypeRef collectionType = Type(Collection.class);
		List<TypeRef> typeArguments = field.typeArguments();
		if (typeArguments.size() == 1) {
			elementType = typeArguments.get(0);
			collectionType.withTypeArgument(Wildcard(EXTENDS, elementType));
		}

		{ // add
			String addMethodName = singular(camelCase(builderData.getPrefix(), field.name()));
			final Argument arg0 = Arg(elementType, "arg0").makeFinal();
			builderMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), addMethodName).makePublic().implementing().withArgument(arg0) //
				.withStatement(Call(Field(field.name()), "add").withArgument(Name("arg0"))) //
				.withStatement(Return(This())));
			interfaceMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), addMethodName).makePublic().withNoBody().withArgument(arg0));
		}
		{ // addAll
			String addAllMethodName = camelCase(builderData.getPrefix(), field.name());
			final Argument arg0 = Arg(collectionType, "arg0").makeFinal();
			builderMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), addAllMethodName).makePublic().implementing().withArgument(arg0) //
				.withStatement(Call(Field(field.name()), "addAll").withArgument(Name("arg0"))) //
				.withStatement(Return(This())));
			interfaceMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), addAllMethodName).makePublic().withNoBody().withArgument(arg0));
		}
	}

	private void createMapMethods(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final FIELD_TYPE field, final List<AbstractMethodDecl<?>> interfaceMethods,
			final List<AbstractMethodDecl<?>> builderMethods) {
		TYPE_TYPE type = builderData.getType();
		TypeRef keyType = Type(Object.class);
		TypeRef valueType = Type(Object.class);
		TypeRef mapType = Type(Map.class);
		List<TypeRef> typeArguments = field.typeArguments();
		if (typeArguments.size() == 2) {
			keyType = typeArguments.get(0);
			valueType = typeArguments.get(1);
			mapType.withTypeArgument(Wildcard(EXTENDS, keyType)) //
				.withTypeArgument(Wildcard(EXTENDS, valueType));
		}

		{ // put
			final String putMethodName = singular(camelCase(builderData.getPrefix(), field.name()));
			final Argument arg0 = Arg(keyType, "arg0").makeFinal();
			final Argument arg1 = Arg(valueType, "arg1").makeFinal();
			builderMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), putMethodName).makePublic().implementing().withArgument(arg0).withArgument(arg1) //
				.withStatement(Call(Field(field.name()), "put").withArgument(Name("arg0")).withArgument(Name("arg1"))) //
				.withStatement(Return(This())));
			interfaceMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), putMethodName).makePublic().withNoBody().withArgument(arg0).withArgument(arg1));
		}
		{ // putAll
			String putAllMethodName = camelCase(builderData.getPrefix(), field.name());
			final Argument arg0 = Arg(mapType, "arg0").makeFinal();
			builderMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), putAllMethodName).makePublic().implementing().withArgument(arg0) //
				.withStatement(Call(Field(field.name()), "putAll").withArgument(Name("arg0"))) //
				.withStatement(Return(This())));
			interfaceMethods.add(MethodDecl(Type(OPTIONAL_DEF).withTypeArguments(type.typeArguments()), putAllMethodName).makePublic().withNoBody().withArgument(arg0));
		}
	}

	private void createBuildMethod(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final String typeName, final List<AbstractMethodDecl<?>> interfaceMethods,
			final List<AbstractMethodDecl<?>> builderMethods) {
		TYPE_TYPE type = builderData.getType();
		builderMethods.add(MethodDecl(Type(typeName).withTypeArguments(type.typeArguments()), "build").makePublic().implementing() //
			.withStatement(Return(New(Type(typeName).withTypeArguments(type.typeArguments())).withArgument(This()))));
		interfaceMethods.add(MethodDecl(Type(typeName).withTypeArguments(type.typeArguments()), "build").makePublic().withNoBody());
	}

	private void createMethodCall(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final String methodName, final List<AbstractMethodDecl<?>> interfaceMethods,
			final List<AbstractMethodDecl<?>> builderMethods) {
		TYPE_TYPE type = builderData.getType();

		TypeRef returnType = Type("void");
		boolean returnsVoid = true;
		List<TypeRef> thrownExceptions = new ArrayList<TypeRef>();
		if ("toString".equals(methodName)) {
			returnType = Type(String.class);
			returnsVoid = false;
		} else {
			for (METHOD_TYPE method : type.methods()) {
				if (methodName.equals(method.name()) && !method.hasArguments()) {
					returnType = method.returns();
					returnsVoid = method.returns("void");
					thrownExceptions.addAll(method.thrownExceptions());
					break;
				}
			}
		}

		Call call = Call(Call("build"), methodName);
		if (returnsVoid) {
			builderMethods.add(MethodDecl(returnType, methodName).makePublic().implementing().withThrownExceptions(thrownExceptions) //
				.withStatement(call));
		} else {
			builderMethods.add(MethodDecl(returnType, methodName).makePublic().implementing().withThrownExceptions(thrownExceptions) //
				.withStatement(Return(call)));
		}
		interfaceMethods.add(MethodDecl(returnType, methodName).makePublic().withNoBody().withThrownExceptions(thrownExceptions));
	}

	private void createBuilder(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData, final List<TypeRef> interfaceTypes,
			final List<AbstractMethodDecl<?>> builderMethods) {
		TYPE_TYPE type = builderData.getType();
		builderMethods.add(ConstructorDecl(BUILDER).makePrivate().withImplicitSuper());
		type.injectType(ClassDecl(BUILDER).withTypeParameters(type.typeParameters()).makePrivate().makeStatic().implementing(interfaceTypes) //
			.withFields(createBuilderFields(builderData)).withMethods(builderMethods));
	}

	private List<FieldDecl> createBuilderFields(final IBuilderData<TYPE_TYPE, METHOD_TYPE, FIELD_TYPE> builderData) {
		List<FieldDecl> fields = new ArrayList<FieldDecl>();
		for (FIELD_TYPE field : builderData.getAllFields()) {
			FieldDecl builder = FieldDecl(field.type(), field.name()).makePrivate();
			if (field.isInitialized()) {
				builder.withInitialization(field.initialization());
				field.replaceInitialization(null);
			}
			fields.add(builder);
		}
		return fields;
	}
	
	public boolean isInitializedMapOrCollection(final FIELD_TYPE field) {
		return (isMap(field) || isCollection(field)) && field.isInitialized();
	}

	private boolean isCollection(final FIELD_TYPE field) {
		return (field.isOfType("Collection") || field.isOfType("List") || field.isOfType("Set"));
	}

	private boolean isMap(final FIELD_TYPE field) {
		return field.isOfType("Map");
	}

	protected abstract void collectExtensions(METHOD_TYPE method, IExtensionCollector collector);

	public static interface IExtensionCollector {
		public IExtensionCollector withRequiredFieldNames(final List<String> fieldNames);

		public boolean isRequiredFieldsExtension();

		public boolean isExtension();
	}

	public static interface IBuilderData<TYPE_TYPE extends IType<METHOD_TYPE, ?, ?, ?, ?, ?>, METHOD_TYPE extends IMethod<TYPE_TYPE, ?, ?, ?>, FIELD_TYPE extends IField<?, ?, ?>> {

		public TYPE_TYPE getType();

		public AccessLevel getLevel();

		public String getPrefix();

		public IExtensionCollector getExtensionCollector();

		public List<String> getCallMethods();

		public List<FIELD_TYPE> getAllFields();

		public List<FIELD_TYPE> getRequiredFields();

		public List<FIELD_TYPE> getOptionalFields();

		public List<TypeRef> getRequiredFieldDefTypes();

		public List<String> getAllRequiredFieldNames();

		public List<String> getRequiredFieldDefTypeNames();

		public boolean isGenerateConvenientMethodsEnabled();
	}
}
