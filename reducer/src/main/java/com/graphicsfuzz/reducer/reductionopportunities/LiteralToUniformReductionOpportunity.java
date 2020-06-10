/*
 * Copyright 2020 The GraphicsFuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphicsfuzz.reducer.reductionopportunities;

import com.graphicsfuzz.common.ast.IParentMap;
import com.graphicsfuzz.common.ast.TranslationUnit;
import com.graphicsfuzz.common.ast.decl.ArrayInfo;
import com.graphicsfuzz.common.ast.decl.Declaration;
import com.graphicsfuzz.common.ast.decl.VariableDeclInfo;
import com.graphicsfuzz.common.ast.decl.VariablesDeclaration;
import com.graphicsfuzz.common.ast.expr.ArrayIndexExpr;
import com.graphicsfuzz.common.ast.expr.ConstantExpr;
import com.graphicsfuzz.common.ast.expr.FloatConstantExpr;
import com.graphicsfuzz.common.ast.expr.IntConstantExpr;
import com.graphicsfuzz.common.ast.expr.VariableIdentifierExpr;
import com.graphicsfuzz.common.ast.type.BasicType;
import com.graphicsfuzz.common.ast.type.QualifiedType;
import com.graphicsfuzz.common.ast.type.TypeQualifier;
import com.graphicsfuzz.common.ast.visitors.VisitationDepth;
import com.graphicsfuzz.common.transformreduce.ShaderJob;
import com.graphicsfuzz.util.Constants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class LiteralToUniformReductionOpportunity
    extends AbstractReductionOpportunity {

  private final ConstantExpr literalExpr;
  private final TranslationUnit translationUnit;
  private final ShaderJob shaderJob;
  private final Number numericValue;
  private final String arrayName;
  private final BasicType basicType;

  LiteralToUniformReductionOpportunity(ConstantExpr literalExpr,
                                       TranslationUnit translationUnit,
                                       ShaderJob shaderJob,
                                       VisitationDepth depth) {

    super(depth);

    this.literalExpr = literalExpr;
    this.translationUnit = translationUnit;
    this.shaderJob = shaderJob;

    // If the uniform array doesn't exist in the pipeline info adds an empty array
    if (literalExpr instanceof IntConstantExpr) {
      this.arrayName = Constants.INT_LITERAL_UNIFORM_VALUES;
      this.basicType = BasicType.INT;
      this.numericValue = ((IntConstantExpr) literalExpr).getNumericValue();
    } else {
      this.arrayName = Constants.FLOAT_LITERAL_UNIFORM_VALUES;
      this.basicType = BasicType.FLOAT;
      this.numericValue = Float.valueOf(((FloatConstantExpr) literalExpr).getValue());
    }

    if (!shaderJob.getPipelineInfo().hasUniform(this.arrayName)) {
      shaderJob.getPipelineInfo().addUniform(this.arrayName, this.basicType,
          Optional.of(0), new ArrayList<>());
      shaderJob.getPipelineInfo().addUniformBinding(this.arrayName, false,
          shaderJob.getPipelineInfo().getNumUniforms());
    }
  }

  @Override
  void applyReductionImpl() {

    // Adds the literal to the array if it doesn't exist already and updates the index number to
    // point at the corresponding element in the array.
    final int index;
    final List<Number> values = this.shaderJob.getPipelineInfo().getArgs(this.arrayName);

    if (values.contains(this.numericValue)) {
      index = values.indexOf(this.numericValue);
    } else {
      index = this.shaderJob.getPipelineInfo().getArgs(this.arrayName).size();
      values.add(this.numericValue);
    }

    // Removes the old uniform array and adds the new one to the associated json structure.
    this.shaderJob.getPipelineInfo().removeUniform(this.arrayName);
    this.shaderJob.getPipelineInfo().addUniform(this.arrayName, this.basicType,
        Optional.of(values.size()), values);
    this.shaderJob.getPipelineInfo().addUniformBinding(this.arrayName, false,
        this.shaderJob.getPipelineInfo().getNumUniforms());

    // Adds the new array declaration to every translation unit.
    for (TranslationUnit tu: shaderJob.getShaders()) {

      // Declares the array if it didn't exist. Otherwise, removes it and adds a new declaration
      // with increased size.
      Optional<Declaration> oldDeclaration = Optional.empty();
      final List<VariablesDeclaration> declarations = tu.getUniformDecls();
      for (VariablesDeclaration declaration : declarations) {
        if (declaration.getDeclInfo(0).getName().equals(this.arrayName)) {
          oldDeclaration = Optional.of(declaration);
        }
      }
      oldDeclaration.ifPresent(tu::removeTopLevelDeclaration);

      final ArrayInfo arrayInfo = new ArrayInfo(new IntConstantExpr(String.valueOf(values.size())));
      final VariableDeclInfo variableDeclInfo = new VariableDeclInfo(this.arrayName,
          arrayInfo, null);
      final VariablesDeclaration asd = new VariablesDeclaration(
          new QualifiedType(this.basicType, Arrays.asList(TypeQualifier.UNIFORM)), variableDeclInfo
      );

      tu.addDeclaration(asd);
    }

    // Replaces the literal with an element in the uniform array.
    final IParentMap parentMap = IParentMap.createParentMap(this.translationUnit);
    final ArrayIndexExpr aie = new ArrayIndexExpr(new VariableIdentifierExpr(this.arrayName),
        new IntConstantExpr(String.valueOf(index)));

    parentMap.getParent(this.literalExpr).replaceChild(this.literalExpr, aie);
  }

  @Override
  public boolean preconditionHolds() {
    return true;
  }

}
