/*
 *  Copyright 2015 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.model.emit;

import org.teavm.model.BasicBlock;
import org.teavm.model.FieldReference;
import org.teavm.model.Incoming;
import org.teavm.model.MethodReference;
import org.teavm.model.Phi;
import org.teavm.model.PrimitiveType;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.ArrayLengthInstruction;
import org.teavm.model.instructions.BinaryBranchingCondition;
import org.teavm.model.instructions.BinaryBranchingInstruction;
import org.teavm.model.instructions.BinaryInstruction;
import org.teavm.model.instructions.BinaryOperation;
import org.teavm.model.instructions.BranchingCondition;
import org.teavm.model.instructions.BranchingInstruction;
import org.teavm.model.instructions.CastInstruction;
import org.teavm.model.instructions.CastIntegerDirection;
import org.teavm.model.instructions.CastIntegerInstruction;
import org.teavm.model.instructions.CastNumberInstruction;
import org.teavm.model.instructions.CloneArrayInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.model.instructions.GetElementInstruction;
import org.teavm.model.instructions.GetFieldInstruction;
import org.teavm.model.instructions.IntegerSubtype;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.IsInstanceInstruction;
import org.teavm.model.instructions.NumericOperandType;
import org.teavm.model.instructions.PutElementInstruction;
import org.teavm.model.instructions.PutFieldInstruction;
import org.teavm.model.instructions.RaiseInstruction;
import org.teavm.model.instructions.UnwrapArrayInstruction;

/**
 *
 * @author Alexey Andreev
 */
public class ValueEmitter {
    ProgramEmitter pe;
    BasicBlock block;
    Variable variable;
    ValueType type;

    ValueEmitter(ProgramEmitter programEmitter, BasicBlock block, Variable variable, ValueType type) {
        this.pe = programEmitter;
        this.block = block;
        this.variable = variable;
        this.type = type;
    }

    public ProgramEmitter getProgramEmitter() {
        return pe;
    }

    public BasicBlock getBlock() {
        return block;
    }

    public Variable getVariable() {
        return variable;
    }

    public ValueType getType() {
        return type;
    }

    public ValueEmitter getField(String name, ValueType type) {
        if (!(type instanceof ValueType.Object)) {
            throw new IllegalStateException("Can't get field of non-object type: " + type);
        }

        String className = ((ValueType.Object) type).getClassName();
        Variable var = pe.getProgram().createVariable();
        GetFieldInstruction insn = new GetFieldInstruction();
        insn.setField(new FieldReference(className, name));
        insn.setFieldType(type);
        insn.setReceiver(var);
        insn.setInstance(variable);
        pe.addInstruction(insn);
        return pe.var(var, type);
    }

    public ValueEmitter getField(String name, Class<?> type) {
        return getField(name, ValueType.parse(type));
    }

    public void setField(String name, ValueEmitter value) {
        if (!(type instanceof ValueType.Object)) {
            throw new IllegalStateException("Can't get field of non-object type: " + type);
        }

        String className = ((ValueType.Object) type).getClassName();
        PutFieldInstruction insn = new PutFieldInstruction();
        insn.setField(new FieldReference(className, name));
        insn.setFieldType(type);
        insn.setInstance(variable);
        insn.setValue(value.getVariable());
        pe.addInstruction(insn);
    }

    static class Pair {
        ValueEmitter first;
        ValueEmitter second;

        public Pair(ValueEmitter first, ValueEmitter second) {
            this.first = first;
            this.second = second;
        }
    }

    private Pair commonNumeric(ValueEmitter other) {
        if (!(type instanceof ValueType.Primitive)) {
            throw new IllegalArgumentException("First argument is not a primitive: " + type);
        }
        if (!(other.type instanceof ValueType.Primitive)) {
            throw new IllegalArgumentException("First argument is not a primitive: " + other.type);
        }

        PrimitiveType firstType = ((ValueType.Primitive) type).getKind();
        PrimitiveType secondType = ((ValueType.Primitive) other.type).getKind();

        if (firstType == PrimitiveType.BOOLEAN) {
            throw new IllegalArgumentException("First argument is not numeric: " + type);
        }
        if (secondType == PrimitiveType.BOOLEAN) {
            throw new IllegalArgumentException("Second argument is not numeric: " + other.type);
        }

        ValueEmitter a = this;
        ValueEmitter b = other;

        IntegerSubtype firstSubtype = convertToIntegerSubtype(firstType);
        if (firstSubtype != null) {
            a = castFromInteger(firstSubtype);
            firstType = PrimitiveType.INTEGER;
        }
        IntegerSubtype secondSubtype = convertToIntegerSubtype(secondType);
        if (secondSubtype != null) {
            b = castFromInteger(secondSubtype);
            secondType = PrimitiveType.INTEGER;
        }

        NumericOperandType firstNumeric = convertToNumeric(firstType);
        NumericOperandType secondNumeric = convertToNumeric(secondType);
        int commonIndex = Math.max(firstNumeric.ordinal(), secondNumeric.ordinal());
        NumericOperandType common = NumericOperandType.values()[commonIndex];
        ValueType commonType = ValueType.primitive(convertNumeric(common));

        if (firstNumeric != common) {
            CastNumberInstruction insn = new CastNumberInstruction(firstNumeric, common);
            insn.setValue(a.getVariable());
            a = pe.newVar(commonType);
            insn.setReceiver(a.getVariable());
            pe.addInstruction(insn);
        }
        if (secondNumeric != common) {
            CastNumberInstruction insn = new CastNumberInstruction(secondNumeric, common);
            insn.setValue(b.getVariable());
            b = pe.newVar(commonType);
            insn.setReceiver(b.getVariable());
            pe.addInstruction(insn);
        }

        return new Pair(a, b);
    }

    private ValueEmitter binary(BinaryOperation op, ValueEmitter other) {
        Pair pair = commonNumeric(other);
        return binaryOp(op, pair.first, pair.second, pair.first.type);
    }

    private ValueEmitter binaryOp(BinaryOperation op, ValueEmitter a, ValueEmitter b, ValueType type) {
        Variable var = pe.getProgram().createVariable();
        PrimitiveType common = ((ValueType.Primitive) a.type).getKind();

        BinaryInstruction insn = new BinaryInstruction(op, convertToNumeric(common));
        insn.setFirstOperand(a.getVariable());
        insn.setSecondOperand(b.getVariable());
        insn.setReceiver(var);
        pe.addInstruction(insn);
        return pe.var(var, type);
    }

    private IntegerSubtype convertToIntegerSubtype(PrimitiveType type) {
        switch (type) {
            case BYTE:
                return IntegerSubtype.BYTE;
            case SHORT:
                return IntegerSubtype.SHORT;
            case CHARACTER:
                return IntegerSubtype.CHARACTER;
            default:
                break;
        }
        return null;
    }

    private NumericOperandType convertToNumeric(PrimitiveType type) {
        switch (type) {
            case BYTE:
            case SHORT:
            case CHARACTER:
            case INTEGER:
                return NumericOperandType.INT;
            case LONG:
                return NumericOperandType.LONG;
            case FLOAT:
                return NumericOperandType.FLOAT;
            case DOUBLE:
                return NumericOperandType.DOUBLE;
            default:
                break;
        }
        throw new AssertionError("Unexpected type: " + type);
    }

    private PrimitiveType convertNumeric(NumericOperandType type) {
        switch (type) {
            case INT:
                return PrimitiveType.INTEGER;
            case LONG:
                return PrimitiveType.LONG;
            case FLOAT:
                return PrimitiveType.FLOAT;
            case DOUBLE:
                return PrimitiveType.DOUBLE;
            default:
                break;
        }
        throw new AssertionError("Unknown type: " + type);
    }

    public ValueEmitter add(ValueEmitter other) {
        return binary(BinaryOperation.ADD, other);
    }

    public ValueEmitter add(int value) {
        return binary(BinaryOperation.ADD, pe.constant(value));
    }

    public ValueEmitter sub(ValueEmitter other) {
        return binary(BinaryOperation.SUBTRACT, other);
    }

    public ValueEmitter sub(int value) {
        return binary(BinaryOperation.SUBTRACT, pe.constant(value));
    }

    public ValueEmitter mul(ValueEmitter other) {
        return binary(BinaryOperation.MULTIPLY, other);
    }

    public ValueEmitter mul(int value) {
        return binary(BinaryOperation.MULTIPLY, pe.constant(value));
    }

    public ValueEmitter div(ValueEmitter other) {
        return binary(BinaryOperation.DIVIDE, other);
    }

    public ValueEmitter div(int value) {
        return binary(BinaryOperation.DIVIDE, pe.constant(value));
    }

    public ValueEmitter rem(ValueEmitter other) {
        return binary(BinaryOperation.MODULO, other);
    }

    public ValueEmitter rem(int value) {
        return binary(BinaryOperation.MODULO, pe.constant(value));
    }

    public ValueEmitter compareTo(ValueEmitter other) {
        Pair pair = commonNumeric(other);
        return binaryOp(BinaryOperation.COMPARE, pair.first, pair.second, ValueType.INTEGER);
    }

    public ValueEmitter compareTo(int value) {
        return compareTo(pe.constant(value));
    }

    private ValueEmitter logical(BinaryOperation op, ValueEmitter other) {
        Pair pair = commonNumeric(other);
        PrimitiveType common = ((ValueType.Primitive) pair.first.type).getKind();
        checkInteger(common);
        return binaryOp(op, pair.first, pair.second, pair.first.type);
    }

    public ValueEmitter bitAnd(ValueEmitter other) {
        return logical(BinaryOperation.AND, other);
    }

    private void checkInteger(PrimitiveType common) {
        switch (common) {
            case FLOAT:
            case DOUBLE:
                throw new IllegalArgumentException("Can't perform bitwise operation between non-integers");
            default:
                break;
        }
    }

    public ValueEmitter bitAnd(int value) {
        return bitAnd(pe.constant(value));
    }

    public ValueEmitter bitOr(ValueEmitter other) {
        return logical(BinaryOperation.OR, other);
    }

    public ValueEmitter bitOr(int value) {
        return bitOr(pe.constant(value));
    }

    public ValueEmitter bitXor(ValueEmitter other) {
        return logical(BinaryOperation.XOR, other);
    }

    public ValueEmitter bitXor(int value) {
        return bitXor(pe.constant(value));
    }

    public ValueEmitter shl(ValueEmitter other) {
        return binary(BinaryOperation.SHIFT_LEFT, other);
    }

    public ValueEmitter shl(int value) {
        return binary(BinaryOperation.SHIFT_LEFT, pe.constant(value));
    }

    public ValueEmitter shr(ValueEmitter other) {
        return binary(BinaryOperation.SHIFT_RIGHT, other);
    }

    public ValueEmitter shr(int value) {
        return binary(BinaryOperation.SHIFT_RIGHT, pe.constant(value));
    }

    public ValueEmitter shru(ValueEmitter other) {
        return binary(BinaryOperation.SHIFT_RIGHT_UNSIGNED, other);
    }

    public ValueEmitter shru(int value) {
        return binary(BinaryOperation.SHIFT_RIGHT_UNSIGNED, pe.constant(value));
    }

    public ValueEmitter invoke(InvocationType invokeType, String className, String name, ValueType resultType,
            ValueEmitter... arguments) {
        if (!(type instanceof ValueType.Object)) {
            throw new IllegalStateException("Can't invoke method on non-object type: " + type);
        }

        Variable result = null;
        String className = ((ValueType.Object) type).getClassName();
        ValueType[] signature = new ValueType[arguments.length + 1];
        for (int i = 0; i < arguments.length; ++i) {
            signature[i] = arguments[i].type;
        }
        signature[arguments.length] = resultType;

        MethodReference method = new MethodReference(className, name, signature);
        if (method.getReturnType() != ValueType.VOID) {
            result = pe.getProgram().createVariable();
        }
        InvokeInstruction insn = new InvokeInstruction();
        insn.setType(invokeType);
        insn.setMethod(method);
        insn.setInstance(variable);
        insn.setReceiver(result);
        for (ValueEmitter arg : arguments) {
            insn.getArguments().add(arg.variable);
        }
        pe.addInstruction(insn);
        return result != null ? pe.var(result, resultType) : null;
    }

    public ValueEmitter invoke(InvocationType invokeType, String name, ValueType resultType,
            ValueEmitter... arguments) {
        return invoke(invokeType, ((ValueType.Object) type).getClassName(), name, resultType, arguments);
    }

    public ValueEmitter invokeSpecial(String className, String name, ValueType resultType, ValueEmitter... arguments) {
        return invoke(InvocationType.SPECIAL, className, name, resultType, arguments);
    }

    public ValueEmitter invokeSpecial(String name, ValueType resultType, ValueEmitter... arguments) {
        return invoke(InvocationType.SPECIAL, name, resultType, arguments);
    }

    public ValueEmitter invokeSpecial(String name, Class<?> resultType, ValueEmitter... arguments) {
        return invoke(InvocationType.SPECIAL, name, ValueType.parse(resultType), arguments);
    }

    public ProgramEmitter invokeSpecial(String className, String name, ValueEmitter... arguments) {
        invokeSpecial(className, name, ValueType.VOID, arguments);
        return pe;
    }

    public ProgramEmitter invokeSpecial(Class<?> cls, String name, ValueEmitter... arguments) {
        invokeSpecial(cls.getName(), name, ValueType.VOID, arguments);
        return pe;
    }

    public ProgramEmitter invokeSpecial(String name, ValueEmitter... arguments) {
        invokeSpecial(name, ValueType.VOID, arguments);
        return pe;
    }

    public ValueEmitter invokeVirtual(String name, ValueType resultType, ValueEmitter... arguments) {
        return invoke(InvocationType.VIRTUAL, name, resultType, arguments);
    }

    public ValueEmitter invokeVirtual(String name, Class<?> resultType, ValueEmitter... arguments) {
        return invoke(InvocationType.VIRTUAL, name, ValueType.parse(resultType), arguments);
    }

    public void invokeVirtual(String name, ValueEmitter... arguments) {
        invokeVirtual(name, ValueType.VOID, arguments);
    }

    public ValueEmitter join(BasicBlock block, ValueEmitter other, BasicBlock otherBlock, ValueType type) {
        Variable var = pe.getProgram().createVariable();
        Phi phi = new Phi();
        phi.setReceiver(var);
        Incoming incoming = new Incoming();
        incoming.setSource(block);
        incoming.setValue(variable);
        phi.getIncomings().add(incoming);
        incoming = new Incoming();
        incoming.setSource(otherBlock);
        incoming.setValue(other.variable);
        phi.getIncomings().add(incoming);
        pe.getBlock().getPhis().add(phi);
        return new ValueEmitter(pe, pe.getBlock(), var, type);
    }

    public ForkEmitter fork(BinaryBranchingCondition condition, ValueEmitter other) {
        final BinaryBranchingInstruction insn = new BinaryBranchingInstruction(condition);
        insn.setFirstOperand(variable);
        insn.setSecondOperand(other.variable);
        pe.addInstruction(insn);
        return new ForkEmitter(pe) {
            @Override public ForkEmitter setThen(BasicBlock block) {
                insn.setConsequent(block);
                return this;
            }
            @Override public ForkEmitter setElse(BasicBlock block) {
                insn.setAlternative(block);
                return this;
            }
        };
    }

    public ForkEmitter fork(BranchingCondition condition) {
        final BranchingInstruction insn = new BranchingInstruction(condition);
        insn.setOperand(variable);
        pe.addInstruction(insn);
        return new ForkEmitter(pe) {
            @Override public ForkEmitter setThen(BasicBlock block) {
                insn.setConsequent(block);
                return this;
            }
            @Override public ForkEmitter setElse(BasicBlock block) {
                insn.setAlternative(block);
                return this;
            }
        };
    }

    public void returnValue() {
        ExitInstruction insn = new ExitInstruction();
        insn.setValueToReturn(variable);
        pe.addInstruction(insn);
    }

    public void raise() {
        RaiseInstruction insn = new RaiseInstruction();
        insn.setException(variable);
        pe.addInstruction(insn);
    }

    public ValueEmitter cast(Class<?> type) {
        return cast(ValueType.parse(type));
    }

    public ValueEmitter cast(ValueType type) {
        if (type.equals(this.type)) {
            return this;
        }
        if (type instanceof ValueType.Primitive) {

        } else {
            if (this.type instanceof ValueType.Primitive) {
                throw new IllegalStateException("Can't convert " + this.type + " to " + type);
            }
            Variable result = pe.getProgram().createVariable();
            CastInstruction insn = new CastInstruction();
            insn.setValue(variable);
            insn.setReceiver(result);
            insn.setTargetType(type);
            pe.addInstruction(insn);
            return pe.var(result, type);
        }
    }

    public ValueEmitter cast(NumericOperandType to) {
        if (!(type instanceof ValueType.Primitive)) {
            throw new IllegalStateException("Can't cast non-primitive type: " + type);
        }

        ValueEmitter value = this;
        PrimitiveType kind = ((ValueType.Primitive) type).getKind();
        IntegerSubtype subtype = convertToIntegerSubtype(kind);
        if (subtype != null) {
            value = value.castFromInteger(subtype);
            kind = PrimitiveType.INTEGER;
        }

        ValueEmitter result = pe.newVar(ValueType.INTEGER);
        CastNumberInstruction insn = new CastNumberInstruction(convertToNumeric(kind), to);
        insn.setValue(variable);
        insn.setReceiver(result.getVariable());
        pe.addInstruction(insn);

        return result;
    }

    private ValueEmitter castFromInteger(IntegerSubtype subtype) {
        CastIntegerInstruction insn = new CastIntegerInstruction(subtype, CastIntegerDirection.TO_INTEGER);
        insn.setValue(variable);
        ValueEmitter result = pe.newVar(ValueType.INTEGER);
        insn.setReceiver(result.getVariable());
        pe.addInstruction(insn);
        return result;
    }

    private ValueEmitter castToInteger(IntegerSubtype subtype) {
        CastIntegerInstruction insn = new CastIntegerInstruction(subtype, CastIntegerDirection.FROM_INTEGER);
        insn.setValue(variable);
        ValueEmitter result = pe.newVar(ValueType.INTEGER);
        insn.setReceiver(result.getVariable());
        pe.addInstruction(insn);
        return result;
    }

    public ValueEmitter getElement(ValueEmitter index) {
        if (!(type instanceof ValueType.Array)) {
            throw new IllegalArgumentException("Can't get element of non-array type: " + type);
        }

        Variable result = pe.getProgram().createVariable();
        GetElementInstruction insn = new GetElementInstruction();
        insn.setArray(variable);
        insn.setIndex(index.variable);
        insn.setReceiver(result);
        pe.addInstruction(insn);
        return pe.var(result, ((ValueType.Array) type).getItemType());
    }

    public ValueEmitter getElement(int index) {
        return getElement(pe.constant(index));
    }

    public void setElement(ValueEmitter index, ValueEmitter value) {
        PutElementInstruction insn = new PutElementInstruction();
        insn.setArray(variable);
        insn.setIndex(index.variable);
        insn.setValue(value.variable);
        pe.addInstruction(insn);
    }

    public void setElement(int index, ValueEmitter value) {
        setElement(pe.constant(index), value);
    }

    public ValueEmitter unwrapArray() {
        Variable result = pe.getProgram().createVariable();
        UnwrapArrayInstruction insn = new UnwrapArrayInstruction(elementType);
        insn.setArray(variable);
        insn.setReceiver(result);
        pe.addInstruction(insn);
        return pe.var(result, type);
    }

    public ValueEmitter arrayLength() {
        Variable result = pe.getProgram().createVariable();
        ArrayLengthInstruction insn = new ArrayLengthInstruction();
        insn.setArray(variable);
        insn.setReceiver(result);
        pe.addInstruction(insn);
        return pe.var(result, ValueType.INTEGER);
    }

    public ValueEmitter instanceOf(ValueType type) {
        Variable result = pe.getProgram().createVariable();
        IsInstanceInstruction insn = new IsInstanceInstruction();
        insn.setValue(variable);
        insn.setReceiver(result);
        insn.setType(type);
        pe.addInstruction(insn);
        return pe.var(result, ValueType.BOOLEAN);
    }

    public ValueEmitter cloneArray() {
        Variable result = pe.getProgram().createVariable();
        CloneArrayInstruction insn = new CloneArrayInstruction();
        insn.setArray(variable);
        insn.setReceiver(result);
        pe.addInstruction(insn);
        return pe.var(result, type);
    }
}