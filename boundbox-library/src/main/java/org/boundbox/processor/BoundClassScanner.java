package org.boundbox.processor;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementKindVisitor6;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;

import org.boundbox.model.ClassInfo;
import org.boundbox.model.FieldInfo;
import org.boundbox.model.InnerClassInfo;
import org.boundbox.model.MethodInfo;

/**
 * Scans a given {@link TypeElement} to produce its associated {@link ClassInfo}. This class is
 * based on the visitor design pattern and uses a {@link ScanningContext} to memorize information
 * during tree visit (and avoid using a stack).
 * @author SNI
 */
@Log
public class BoundClassScanner extends ElementKindVisitor6<Void, ScanningContext> {

    @Getter
    @Setter
    private String maxSuperClassName = Object.class.getName();
    private ClassInfo initialclassInfo;
    private List<String> visitiedTypes = new ArrayList<String>();
    @Getter
    private List<String> listOfInvisibleTypes = new ArrayList<String>();
    
    private VisbilityComputer visbilityComputer = new VisbilityComputer();

    public ClassInfo scan(TypeElement boundClass) {
        visitiedTypes.clear();
        listOfInvisibleTypes.clear();
        initialclassInfo = new ClassInfo(boundClass.getQualifiedName().toString());
        ScanningContext initialScanningContext = new ScanningContext(initialclassInfo);
        boundClass.accept(this, initialScanningContext);
        initialclassInfo.getListImports().remove(boundClass.toString());
        maxSuperClassName = Object.class.getName();
        return initialclassInfo;
    }
    
    public void setBoundBoxPackageName(String boundBoxPackageName) {
        this.visbilityComputer.setBoundBoxPackageName(boundBoxPackageName);
    }

    public void setMaxSuperClass(Class<?> maxSuperClass) {
        this.maxSuperClassName = maxSuperClass.getName();
    }

    @Override
    public Void visitTypeAsClass(TypeElement e, ScanningContext scanningContext) {
        boolean isBoundClass = e.getQualifiedName().toString().equals(initialclassInfo.getClassName());

        if (!isBoundClass && !scanningContext.isInsideEnclosedElements() && !scanningContext.isInsideSuperElements()) {
            log.info("dropping class ->" + e.getSimpleName());
            return null;
        }

        visitiedTypes.add(e.toString());
        
        doCheckVisibilityOfType(e);

        log.info("class ->" + e.getSimpleName());

        boolean isInnerClass = e.getNestingKind().isNested();
        log.info("nested ->" + isInnerClass);

        boolean isStaticElement = e.getModifiers().contains(Modifier.STATIC);
        if( !scanningContext.isInsideSuperElements()) {
            scanningContext.setStatic((!isInnerClass || isStaticElement) && scanningContext.isStatic());
        } else {
            scanningContext.setStatic( isStaticElement && scanningContext.isStatic());
        }

        // boundboxes around inner classes should not be considered as inner classes
        // but otherwise, if we are an inner class
        if (isInnerClass && !isBoundClass) {
            ClassInfo classInfo = scanningContext.getCurrentClassInfo();
            int inheritanceLevel = scanningContext.getInheritanceLevel();
            InnerClassInfo innerClassInfo = new InnerClassInfo(e);
            innerClassInfo.setStaticInnerClass(e.getModifiers().contains(Modifier.STATIC));
            innerClassInfo.setInheritanceLevel(inheritanceLevel);

            // Current element is an inner class and we are currently scanning elements of someone
            // so we add innerclassinfo to that someone : the current class info.
            if (scanningContext.isInsideEnclosedElements()) {
                classInfo.getListInnerClassInfo().add(innerClassInfo);
            }

            // inside super classes we don't change the classInfo being scanned (inheritance
            // flatenning)
            // but outside, we do, to scan the inner class itself.
            if (!scanningContext.isInsideSuperElements()) {
                ScanningContext newScanningContext = new ScanningContext(innerClassInfo);
                newScanningContext.setInheritanceLevel(0);
                newScanningContext.setStatic(isStaticElement && scanningContext.isStatic());
                scanningContext = newScanningContext;
            }
        }

        addTypeToImport(scanningContext.getCurrentClassInfo(), e.asType());

        log.info("super class -> " +e.toString() +"-->"+ e.getSuperclass().toString());
        TypeMirror superclassOfBoundClass = e.getSuperclass();
        boolean hasValidSuperClass = !maxSuperClassName.equals(superclassOfBoundClass.toString()) && !Object.class.getName().equals(superclassOfBoundClass.toString())
                && superclassOfBoundClass.getKind() == TypeKind.DECLARED;

        // if we have a valid inner class, let's scan it
        if (hasValidSuperClass) {
            DeclaredType superClassDeclaredType = (DeclaredType) superclassOfBoundClass;
            Element superClassElement = superClassDeclaredType.asElement();
            scanningContext.getCurrentClassInfo().getListSuperClassNames().add(superClassElement.toString());

            ClassInfo classInfo = scanningContext.getCurrentClassInfo();
            ScanningContext newScanningContext = new ScanningContext(classInfo);
            newScanningContext.setInheritanceLevel(scanningContext.getInheritanceLevel() + 1);
            newScanningContext.setStatic(scanningContext.isStatic());
            newScanningContext.setInsideEnclosedElements(false);
            newScanningContext.setInsideSuperElements(true);
            superClassElement.accept(BoundClassScanner.this, newScanningContext);
        }

        // and finally visit all elements of current class if :
        // it is an inner class of a bound class, or a super class
        // also, root bound class is scanned in current scanning context
        // otherwise, we don't scan
        if (isBoundClass || scanningContext.isInsideSuperElements() || isInnerClass) {
            // http://stackoverflow.com/q/7738171/693752
            for (Element enclosedElement : e.getEnclosedElements()) {
                scanningContext.setInsideEnclosedElements(true);
                scanningContext.setInsideSuperElements(false);
                enclosedElement.accept(this, scanningContext);
            }
            scanningContext.setInsideEnclosedElements(false);
        }

        return super.visitTypeAsClass(e, scanningContext);
    }

    @Override
    public Void visitExecutable(ExecutableElement e, ScanningContext scanningContext) {
        log.info("executable ->" + e.getSimpleName());
        MethodInfo methodInfo = new MethodInfo(e);

        doCheckVisibilityOfTypesInSignature(e, methodInfo);

        // other properties
        if (methodInfo.isConstructor()) {
            if (scanningContext.getInheritanceLevel() == 0) {
                scanningContext.getCurrentClassInfo().getListConstructorInfos().add(methodInfo);
            }
        } else {
            methodInfo.setStaticMethod(e.getModifiers().contains(Modifier.STATIC) && scanningContext.isStatic());
            methodInfo.setInheritanceLevel(scanningContext.getInheritanceLevel());
            // prevents methods overriden in subclass to be re-added in super class.
            scanningContext.getCurrentClassInfo().getListMethodInfos().add(methodInfo);
        }
        addTypeToImport(scanningContext.getCurrentClassInfo(), e.getReturnType());
        for (VariableElement param : e.getParameters()) {
            addTypeToImport(scanningContext.getCurrentClassInfo(), param.asType());
        }
        for (TypeMirror thrownType : e.getThrownTypes()) {
            addTypeToImport(scanningContext.getCurrentClassInfo(), thrownType);
        }

        return super.visitExecutable(e, scanningContext);
    }

    @Override
    public Void visitVariableAsField(VariableElement e, ScanningContext scanningContext) {
        FieldInfo fieldInfo = new FieldInfo(e);
        doCheckVisibilityOfField(e, fieldInfo);
        fieldInfo.setInheritanceLevel(scanningContext.getInheritanceLevel());
        fieldInfo.setStaticField(e.getModifiers().contains(Modifier.STATIC) && scanningContext.isStatic());
        fieldInfo.setFinalField(e.getModifiers().contains(Modifier.FINAL));
        scanningContext.getCurrentClassInfo().getListFieldInfos().add(fieldInfo);
        log.info("field ->" + fieldInfo.getFieldName() + " added. Static = " + fieldInfo.isStaticField());

        addTypeToImport(scanningContext.getCurrentClassInfo(), e.asType());

        return super.visitVariableAsField(e, scanningContext);
    }

    private void addTypeToImport(ClassInfo classInfo, DeclaredType declaredType) {
        log.info("Adding to imports " + declaredType.toString().replaceAll("<.*>", ""));
        // removes parameters from type if it has some
        classInfo.getListImports().add(declaredType.toString().replaceAll("<.*>", ""));
        for (TypeMirror typeArgument : declaredType.getTypeArguments()) {
            addTypeToImport(classInfo, typeArgument);
        }
    }

    private void addTypeToImport(ClassInfo classInfo, TypeMirror typeMirror) {
        if (typeMirror.getKind() == TypeKind.DECLARED) {
            addTypeToImport(classInfo, ((DeclaredType) typeMirror));
        }
    }

    private void doCheckVisibilityOfType(TypeElement e) {
        if (!visbilityComputer.computeVisibility(e)) {
            listOfInvisibleTypes.add(e.getQualifiedName().toString());
        }
    }
    
    private void doCheckVisibilityOfTypesInSignature(ExecutableElement e, MethodInfo methodInfo) {
        if (!visbilityComputer.computeVisibility(e.getReturnType())) {
            TypeElement visibleType = visbilityComputer.findVisibleSuperType(e.getReturnType());
            methodInfo.setReturnTypeName(visibleType.getQualifiedName().toString());
        }
        
        if( methodInfo.isConstructor() ) {
            TypeElement visibleType = visbilityComputer.findVisibleSuperType((TypeElement) e.getEnclosingElement());
            methodInfo.setReturnTypeName(visibleType.getQualifiedName().toString());
        }

        for (int indexParam = 0; indexParam < e.getParameters().size(); indexParam++) {
            VariableElement param = e.getParameters().get(indexParam);
            if (!visbilityComputer.computeVisibility(param.asType())) {
                TypeElement visibleType = visbilityComputer.findVisibleSuperType(param.asType());
                FieldInfo fieldInfo = methodInfo.getParameterTypes().get(indexParam);
                fieldInfo.setFieldTypeName(visibleType.getQualifiedName().toString());
            }
        }

        for (int indexThrownTypes = 0; indexThrownTypes < e.getThrownTypes().size(); indexThrownTypes++) {
            TypeMirror typeMirrorOfException = e.getThrownTypes().get(indexThrownTypes);
            if (!visbilityComputer.computeVisibility(typeMirrorOfException)) {
                TypeElement visibleType = visbilityComputer.findVisibleSuperType(typeMirrorOfException);
                String visibleTypeName = visibleType.getQualifiedName().toString();
                methodInfo.getThrownTypeNames().set(indexThrownTypes, visibleTypeName);
            }
        }
    }
    
    private void doCheckVisibilityOfField(VariableElement e, FieldInfo fieldInfo) {
        TypeMirror typeOfField = e.asType();
        if (!visbilityComputer.computeVisibility(typeOfField)) {
            TypeElement visibleType = visbilityComputer.findVisibleSuperType(typeOfField);
            fieldInfo.setFieldTypeName(visibleType.getQualifiedName().toString());
        }
    }

}
