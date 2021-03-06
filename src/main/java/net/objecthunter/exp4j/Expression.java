/* 
 * Copyright 2014 Frank Asseg
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package net.objecthunter.exp4j;

import net.objecthunter.exp4j.constant.Constants;
import net.objecthunter.exp4j.function.Function;
import net.objecthunter.exp4j.function.Functions;
import net.objecthunter.exp4j.operator.Operator;
import net.objecthunter.exp4j.tokenizer.*;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class Expression {

    private final Token[] tokens;

    private final Map<String, VariableValue> variables = new HashMap<String, VariableValue>();

    private final Set<String> userFunctionNames;

    private final ArrayStack stack = new ArrayStack();

    // cached arrays, should be accessed through #getArray() method. Idea is to avoid too many array allocations when
    // f.e. expression is evaluated in a loop
    private final double[][] arrays = new double[4][];

    {
        for (int i = 0; i < arrays.length; i++) {
            arrays[i] = new double[i];
        }
    }

    /**
     * Creates a new expression that is a copy of the existing one.
     *
     * @param existing the expression to copy
     */
    public Expression(final Expression existing) {
        this.tokens = Arrays.copyOf(existing.tokens, existing.tokens.length);
        this.userFunctionNames = new HashSet<String>(existing.userFunctionNames);
        this.variables.putAll(existing.variables);
    }

    Expression(final Token[] tokens) {
        this.tokens = tokens;
        this.userFunctionNames = Collections.emptySet();
        setVariables(Constants.getBuiltinConstants());
    }

    Expression(final Token[] tokens, Set<String> userFunctionNames) {
        this.tokens = tokens;
        this.userFunctionNames = userFunctionNames;
        setVariables(Constants.getBuiltinConstants());
    }

    public Expression setVariable(final String name, final double value) {
        this.checkVariableName(name);
        VariableValue variableValue = this.variables.get(name);
        if (variableValue == null) {
            variableValue = new VariableValue();
            this.variables.put(name, variableValue);
        }
        variableValue.value = value;
        return this;
    }

    public Expression setVariable(final String name, final Double value) {
        return setVariable(name, value.doubleValue());
    }

    private void checkVariableName(String name) {
        if (this.userFunctionNames.contains(name) || Functions.getBuiltinFunction(name) != null) {
            throw new IllegalArgumentException("The variable name '" + name + "' is invalid. Since there exists a function with the same name");
        }
    }

    public Expression setVariables(Map<String, Double> variables) {
        for (Map.Entry<String, Double> v : variables.entrySet()) {
            this.setVariable(v.getKey(), v.getValue());
        }
        return this;
    }

    public ValidationResult validate(boolean checkVariablesSet) {
        final List<String> errors = new ArrayList<String>(0);
        if (checkVariablesSet) {
            /* check that all vars have a value set */
            for (final Token t : this.tokens) {
                if (t.getType() == Token.TOKEN_VARIABLE) {
                    final String var = ((VariableToken) t).getName();
                    if (!variables.containsKey(var)) {
                        errors.add("The setVariable '" + var + "' has not been set");
                    }
                }
            }
        }

        /* Check if the number of operands, functions and operators match.
           The idea is to increment a counter for operands and decrease it for operators.
           When a function occurs the number of available arguments has to be greater
           than or equals to the function's expected number of arguments.
           The count has to be larger than 1 at all times and exactly 1 after all tokens
           have been processed */
        int count = 0;
        for (Token tok : this.tokens) {
            switch (tok.getType()) {
                case Token.TOKEN_NUMBER:
                case Token.TOKEN_VARIABLE:
                    count++;
                    break;
                case Token.TOKEN_FUNCTION:
                    final Function func = ((FunctionToken) tok).getFunction();
                    final int argsNum = func.getNumArguments();
                    if (argsNum > count) {
                        errors.add("Not enough arguments for '" + func.getName() + "'");
                    }
                    if (argsNum > 1) {
                        count -= argsNum - 1;
                    }
                    break;
                case Token.TOKEN_OPERATOR:
                    Operator op = ((OperatorToken) tok).getOperator();
                    if (op.getNumOperands() == 2) {
                        count--;
                    }
                    break;
            }
            if (count < 1) {
                errors.add("Too many operators");
                return new ValidationResult(false, errors);
            }
        }
        if (count > 1) {
            errors.add("Too many operands");
        }
        return errors.size() == 0 ? ValidationResult.SUCCESS : new ValidationResult(false, errors);

    }

    public ValidationResult validate() {
        return validate(true);
    }

    public Future<Double> evaluateAsync(ExecutorService executor) {
        return executor.submit(new Callable<Double>() {
            @Override
            public Double call() throws Exception {
                return evaluate();
            }
        });
    }

    public double evaluate() {
        stack.reset();
        for (int i = 0; i < tokens.length; i++) {
            Token t = tokens[i];
            if (t.getType() == Token.TOKEN_NUMBER) {
                stack.push(((NumberToken) t).getValue());
            } else if (t.getType() == Token.TOKEN_VARIABLE) {
                final String name = ((VariableToken) t).getName();
                final VariableValue value = this.variables.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("No value has been set for the setVariable '" + name + "'.");
                }
                stack.push(value.value);
            } else if (t.getType() == Token.TOKEN_OPERATOR) {
                final OperatorToken op = (OperatorToken) t;
                final Operator operator = op.getOperator();
                final int numOperands = operator.getNumOperands();
                if (stack.size() < numOperands) {
                    throw new IllegalArgumentException("Invalid number of operands available for '" + operator.getSymbol() + "' operator");
                }
                /* collect the operands from the stack */
                final double[] ops = getArray(numOperands);
                for (int j = numOperands - 1; j >= 0; j--) {
                    ops[j] = stack.pop();
                }
                stack.push(operator.apply(ops));
            } else if (t.getType() == Token.TOKEN_FUNCTION) {
                final FunctionToken func = (FunctionToken) t;
                final Function function = func.getFunction();
                final int numArguments = function.getNumArguments();
                if (stack.size() < numArguments) {
                    throw new IllegalArgumentException("Invalid number of arguments available for '" + function.getName() + "' function");
                }
                /* collect the arguments from the stack */
                final double[] args = getArray(numArguments);
                for (int j = numArguments - 1; j >= 0; j--) {
                    args[j] = stack.pop();
                }
                stack.push(function.apply(args));
            }
        }
        if (stack.size() > 1) {
            throw new IllegalArgumentException("Invalid number of items on the output queue. Might be caused by an invalid number of arguments for a function.");
        }
        return stack.pop();
    }

    private double[] getArray(int size) {
        if (size < arrays.length) {
            return arrays[size];
        }
        return new double[size];
    }

    /**
     * This class is used to avoid Double instantiation for doubles in {@link Expression#variables} map
     */
    private static class VariableValue {
        double value;
    }
}
