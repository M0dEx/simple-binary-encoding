/*
 * Copyright 2013-2023 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.sbe.generation.python;

import org.agrona.Verify;
import org.agrona.generation.OutputManager;
import uk.co.real_logic.sbe.PrimitiveType;
import uk.co.real_logic.sbe.generation.CodeGenerator;
import uk.co.real_logic.sbe.generation.Generators;
import uk.co.real_logic.sbe.generation.java.JavaUtil;
import uk.co.real_logic.sbe.ir.Encoding;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.ir.Signal;
import uk.co.real_logic.sbe.ir.Token;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.*;

import static java.lang.String.format;
import static uk.co.real_logic.sbe.generation.python.RustUtil.*;
import static uk.co.real_logic.sbe.ir.GenerationUtil.*;
import static uk.co.real_logic.sbe.ir.Signal.BEGIN_ENUM;
import static uk.co.real_logic.sbe.ir.Signal.BEGIN_SET;

/**
 * Generate codecs for the Rust programming language.
 */
public class RustPythonGenerator implements CodeGenerator
{
    static final String WRITE_BUF_TYPE = "WriteBuf";
    static final String READ_BUF_TYPE = "ReadBuf";
    static final String BUF_LIFETIME = "'a";

    enum CodecType
    {
        Decoder
        {
            String bufType()
            {
                return READ_BUF_TYPE;
            }
        },

        Encoder
        {
            String bufType()
            {
                return WRITE_BUF_TYPE;
            }
        };

        abstract String bufType();
    }

    interface ParentDef
    {
        SubGroup addSubGroup(String name, int level, Token groupToken);
    }

    private final Ir ir;
    private final RustPythonOutputManager outputManager;

    /**
     * Create a new Rust language {@link CodeGenerator}.
     *
     * @param ir            for the messages and types.
     * @param outputManager for generating the codecs to.
     */
    public RustPythonGenerator(
        final Ir ir,
        final OutputManager outputManager) {
        Verify.notNull(ir, "ir");
        Verify.notNull(outputManager, "outputManager");

        this.ir = ir;
        this.outputManager = (RustPythonOutputManager) outputManager;
    }

    @Override
    public void generate() throws IOException
    {
        final String namespace = determineNamespace();

        // create Cargo.toml
        try (Writer writer = outputManager.createCargoToml())
        {
            // Package
            indent(writer, 0, "[package]\n");
            indent(writer, 0, "name = \"%s\"\n", namespace);
            // TODO: Correct version (from console?)
            indent(writer, 0, "version = \"0.1.0\"\n");
            indent(writer, 0, "description = \"%s\"\n", ir.description());
            // TODO: Authors? Maintainers?
            indent(writer, 0, "authors = [\"sbetool\"]\n");
            indent(writer, 0, "edition = \"2018\"\n\n");

            // Lib
            indent(writer, 0, "[lib]\n");
            indent(writer, 0, "name = \"%s\"\n", namespace);
            indent(writer, 0, "crate-type = [\"cdylib\"]\n");
            indent(writer, 0, "path = \"src/lib.rs\"\n\n");

            // Dependencies
            indent(writer, 0, "[dependencies]\n");
            // TODO: Keep up-to-date
            indent(writer, 0, "pyo3 = \"0.18.1\"\n");
        }

        // create pyproject.toml
        try (Writer writer = outputManager.createPyProjectToml())
        {
            // Package
            indent(writer, 0, "[tool.poetry]\n");
            indent(writer, 0, "name = \"%s\"\n", namespace);
            // TODO: Correct version (from console?)
            indent(writer, 0, "version = \"0.1.0\"\n");
            indent(writer, 0, "description = \"%s\"\n", ir.description());
            // TODO: Authors? Maintainers?
            indent(writer, 0, "authors = [\"sbetool\"]\n\n");

            // Project
            indent(writer, 0, "[project]\n");
            indent(writer, 0, "name = \"%s\"\n", namespace);
            indent(writer, 0, "requires-python = \">=3.9\"\n\n");

            // Maturin
            indent(writer, 0, "[tool.maturin]\n");
            indent(writer, 0, "features = [\"pyo3/extension-module\"]\n\n");

            // Dependencies
            indent(writer, 0, "[tool.poetry.dependencies]\n");
            indent(writer, 0, "python = \"^3.9\"\n");
            // TODO: Keep up-to-date
            indent(writer, 0, "maturin = \"^0.14.15\"\n\n");

            // Build system
            indent(writer, 0, "[build-system]\n");
            // TODO: Keep up-to-date
            indent(writer, 0, "requires = [\"maturin>=0.14,<0.15\"]\n");
            indent(writer, 0, "build-backend = \"maturin\"\n");
        }

        // lib.rs
        final LibRsDef libRsDef = new LibRsDef(outputManager, ir.byteOrder());

        generateEnums(ir, outputManager);
        generateBitSets(ir, outputManager);
        generateComposites(ir, outputManager);

        for (final List<Token> tokens : ir.messages())
        {
            final Token msgToken = tokens.get(0);
            final String codecModName = codecModName(msgToken.name());
            final List<Token> messageBody = tokens.subList(1, tokens.size() - 1);

            int i = 0;
            final List<Token> fields = new ArrayList<>();
            i = collectFields(messageBody, i, fields);

            final List<Token> groups = new ArrayList<>();
            i = collectGroups(messageBody, i, groups);

            final List<Token> varData = new ArrayList<>();
            collectVarData(messageBody, i, varData);

            try (Writer out = outputManager.createOutput(codecModName))
            {
                indent(out, 0, "use pyo3::types::PyType;\n");
                indent(out, 0, "use crate::*;\n\n");
                indent(out, 0, "pub use encoder::*;\n");
                indent(out, 0, "pub use decoder::*;\n\n");
                final String blockLengthType = blockLengthType();
                final String templateIdType = rustTypeName(ir.headerStructure().templateIdType());
                final String schemaIdType = rustTypeName(ir.headerStructure().schemaIdType());
                final String schemaVersionType = schemaVersionType();
                final String semanticVersion = ir.semanticVersion() == null ? "" : ir.semanticVersion();
                indent(out, 0, "pub const SBE_BLOCK_LENGTH: %s = %d;\n", blockLengthType, msgToken.encodedLength());
                indent(out, 0, "pub const SBE_TEMPLATE_ID: %s = %d;\n", templateIdType, msgToken.id());
                indent(out, 0, "pub const SBE_SCHEMA_ID: %s = %d;\n", schemaIdType, ir.id());
                indent(out, 0, "pub const SBE_SCHEMA_VERSION: %s = %d;\n", schemaVersionType, ir.version());
                indent(out, 0, "pub const SBE_SEMANTIC_VERSION: &str = \"%s\";\n\n", semanticVersion);

                MessageCoderDef.generateStruct(fields, formatStructName(msgToken.name()), out);
                MessageCoderDef.generateEncoder(ir, out, msgToken, fields, groups, varData);
                MessageCoderDef.generateDecoder(ir, out, msgToken, fields, groups, varData);
            }
        }

        libRsDef.generate();
    }

    String blockLengthType()
    {
        return rustTypeName(ir.headerStructure().blockLengthType());
    }

    String schemaVersionType()
    {
        return rustTypeName(ir.headerStructure().schemaVersionType());
    }

    String determineNamespace()
    {
        final String packageName = toLowerSnakeCase(ir.packageName()).replaceAll("[.-]", "_");
        final String namespace;
        if (ir.namespaceName() == null || ir.namespaceName().equalsIgnoreCase(packageName))
        {
            namespace = packageName.toLowerCase();
        }
        else
        {
            namespace = (ir.namespaceName() + "_" + packageName).toLowerCase();
        }

        return namespace;
    }

    static String withLifetime(final String typeName)
    {
        return format("%s<%s>", typeName, BUF_LIFETIME);
    }

    static void appendImplWithLifetimeHeader(
        final Appendable appendable,
        final String typeName) throws IOException
    {
        indent(appendable, 1, "impl<%s> %s<%s> {\n", BUF_LIFETIME, typeName, BUF_LIFETIME);
    }

    static String getBufOffset(final Token token)
    {
        final int offset = token.offset();
        if (offset > 0)
        {
            return "offset + " + offset;
        }

        return "offset";
    }

    static void generateEncoderFields(
        final StringBuilder sb,
        final List<Token> tokens,
        final int level)
    {
        Generators.forEachField(
            tokens,
            (fieldToken, typeToken) ->
            {
                try
                {
                    final String name = fieldToken.name();
                    switch (typeToken.signal())
                    {
                        case ENCODING:
                            generatePrimitiveEncoder(sb, level, typeToken, name);
                            break;
                        case BEGIN_ENUM:
                            generateEnumEncoder(sb, level, fieldToken, typeToken, name);
                            break;
                        case BEGIN_SET:
                            generateBitSetEncoder(sb, level, typeToken, name);
                            break;
                        case BEGIN_COMPOSITE:
                            generateCompositeEncoder(sb, level, typeToken, name);
                            break;
                        default:
                            break;
                    }
                }
                catch (final IOException ex)
                {
                    throw new UncheckedIOException(ex);
                }
            });
    }

    static void generateEncoderGroups(
        final StringBuilder sb,
        final List<Token> tokens,
        final int level,
        final ParentDef parentDef) throws IOException
    {
        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token groupToken = tokens.get(i);
            if (groupToken.signal() != Signal.BEGIN_GROUP)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_GROUP: token=" + groupToken);
            }

            ++i;
            final int index = i;
            final int groupHeaderTokenCount = tokens.get(i).componentTokenCount();
            i += groupHeaderTokenCount;

            final List<Token> fields = new ArrayList<>();
            i = collectFields(tokens, i, fields);

            final List<Token> groups = new ArrayList<>();
            i = collectGroups(tokens, i, groups);

            final List<Token> varData = new ArrayList<>();
            i = collectVarData(tokens, i, varData);

            final String groupName = encoderName(formatStructName(groupToken.name()));
            final Token numInGroupToken = Generators.findFirst("numInGroup", tokens, index);
            final PrimitiveType numInGroupPrimitiveType = numInGroupToken.encoding().primitiveType();

            indent(sb, level, "/// GROUP ENCODER\n");
            assert 4 == groupHeaderTokenCount;
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s(self, count: %s, %1$s: %3$s<Self>) -> %3$s<Self> {\n",
                formatFunctionName(groupName),
                rustTypeName(numInGroupPrimitiveType),
                groupName);

            indent(sb, level + 1, "%s.wrap(self, count)\n", toLowerSnakeCase(groupName));
            indent(sb, level, "}\n\n");

            final SubGroup subGroup = parentDef.addSubGroup(groupName, level, groupToken);
            subGroup.generateEncoder(tokens, fields, groups, varData, index);
        }
    }

    static void generateEncoderVarData(
        final StringBuilder sb,
        final List<Token> tokens,
        final int level) throws IOException
    {
        for (int i = 0, size = tokens.size(); i < size; )
        {
            final Token varDataToken = tokens.get(i);
            if (varDataToken.signal() != Signal.BEGIN_VAR_DATA)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_VAR_DATA: token=" + varDataToken);
            }

            final String characterEncoding = characterEncoding(tokens.get(i + 3).encoding());
            final String propertyName = toLowerSnakeCase(varDataToken.name());
            final Token lengthToken = tokens.get(i + 2);
            final Encoding lengthEncoding = lengthToken.encoding();
            final PrimitiveType lengthType = lengthEncoding.primitiveType();

            final String varDataType;
            final String toBytesFn;
            if (JavaUtil.isUtf8Encoding(characterEncoding))
            {
                varDataType = "&str";
                toBytesFn = ".as_bytes()";
            }
            else
            {
                varDataType = "&[u8]";
                toBytesFn = "";
            }

            // function to write slice ... todo - handle character encoding ?
            indent(sb, level, "/// VAR_DATA ENCODER - character encoding: '%s'\n", characterEncoding);
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s(&mut self, value: %s) {\n", propertyName, varDataType);

            indent(sb, level + 1, "let limit = self.get_limit();\n");
            indent(sb, level + 1, "let data_length = value.len();\n");
            indent(sb, level + 1, "self.set_limit(limit + %d + data_length);\n", lengthType.size());

            indent(sb, level + 1,
                "self.get_buf_mut().put_%s_at(limit, data_length as %1$s);\n",
                rustTypeName(lengthType));

            indent(sb, level + 1, "self.get_buf_mut().put_slice_at(limit + %d, value%s);\n",
                lengthType.size(),
                toBytesFn);

            indent(sb, level, "}\n\n");

            i += varDataToken.componentTokenCount();
        }
    }

    static void generatePrimitiveField(
            final StringBuilder sb,
            final int level,
            final Token typeToken,
            final String name
    ) throws IOException {
        final Encoding encoding = typeToken.encoding();
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String rustPrimitiveType = rustTypeName(primitiveType);

        final int arrayLength = typeToken.arrayLength();

        if (arrayLength > 1)
        {
            indent(sb, level, "/// primitive array field '%s'\n", name);
            indent(sb, level, "/// - min value: %s\n", encoding.applicableMinValue());
            indent(sb, level, "/// - max value: %s\n", encoding.applicableMaxValue());
            indent(sb, level, "/// - null value: %s\n", encoding.applicableNullValue());
            indent(sb, level, "/// - characterEncoding: %s\n", encoding.characterEncoding());
            indent(sb, level, "/// - semanticType: %s\n", encoding.semanticType());
            indent(sb, level, "/// - encodedOffset: %d\n", typeToken.offset());
            indent(sb, level, "/// - encodedLength: %d\n", typeToken.encodedLength());
            indent(sb, level, "/// - version: %d\n", typeToken.version());
            indent(sb, level, "#[pyo3(get)]\n");
            indent(sb, level, "%s: [%s; %d],\n\n", toLowerSnakeCase(name), rustPrimitiveType, arrayLength);
        }
        else if (typeToken.isConstantEncoding()) {}
        else
        {
            indent(sb, level, "/// primitive field '%s'\n", name);
            indent(sb, level, "/// - min value: %s\n", encoding.applicableMinValue());
            indent(sb, level, "/// - max value: %s\n", encoding.applicableMaxValue());
            indent(sb, level, "/// - null value: %s\n", encoding.applicableNullValue());
            indent(sb, level, "/// - characterEncoding: %s\n", encoding.characterEncoding());
            indent(sb, level, "/// - semanticType: %s\n", encoding.semanticType());
            indent(sb, level, "/// - encodedOffset: %d\n", typeToken.offset());
            indent(sb, level, "/// - encodedLength: %d\n", typeToken.encodedLength());
            indent(sb, level, "#[pyo3(get)]\n");
            indent(sb, level, "%s: %s,\n\n", toLowerSnakeCase(name), rustPrimitiveType);
        }
    }

    private static void generatePrimitiveEncoder(
        final StringBuilder sb,
        final int level,
        final Token typeToken,
        final String name) throws IOException
    {
        final Encoding encoding = typeToken.encoding();
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String rustPrimitiveType = rustTypeName(primitiveType);
        final int arrayLength = typeToken.arrayLength();
        if (arrayLength > 1)
        {
            indent(sb, level, "/// primitive array field '%s'\n", name);
            indent(sb, level, "/// - min value: %s\n", encoding.applicableMinValue());
            indent(sb, level, "/// - max value: %s\n", encoding.applicableMaxValue());
            indent(sb, level, "/// - null value: %s\n", encoding.applicableNullValue());
            indent(sb, level, "/// - characterEncoding: %s\n", encoding.characterEncoding());
            indent(sb, level, "/// - semanticType: %s\n", encoding.semanticType());
            indent(sb, level, "/// - encodedOffset: %d\n", typeToken.offset());
            indent(sb, level, "/// - encodedLength: %d\n", typeToken.encodedLength());
            indent(sb, level, "/// - version: %d\n", typeToken.version());
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s(&mut self, value: [%s; %d]) {\n",
                formatFunctionName(name),
                rustPrimitiveType,
                arrayLength);

            // NB: must create variable 'offset' before calling mutable self.get_buf_mut()
            indent(sb, level + 1, "let offset = self.%s;\n", getBufOffset(typeToken));
            indent(sb, level + 1, "let buf = self.get_buf_mut();\n");

            for (int i = 0; i < arrayLength; i++)
            {
                if (i == 0)
                {
                    indent(sb, level + 1, "buf.put_%s_at(offset, value[%d]);\n", rustPrimitiveType, i);
                }
                else
                {
                    indent(sb, level + 1, "buf.put_%s_at(offset + %d, value[%d]);\n",
                        rustPrimitiveType,
                        i * primitiveType.size(),
                        i);
                }
            }

            indent(sb, level, "}\n\n");
        }
        else
        {
            if (encoding.presence() == Encoding.Presence.CONSTANT)
            {
                indent(sb, level, "// skipping CONSTANT %s\n\n", name);
            }
            else
            {
                indent(sb, level, "/// primitive field '%s'\n", name);
                indent(sb, level, "/// - min value: %s\n", encoding.applicableMinValue());
                indent(sb, level, "/// - max value: %s\n", encoding.applicableMaxValue());
                indent(sb, level, "/// - null value: %s\n", encoding.applicableNullValue());
                indent(sb, level, "/// - characterEncoding: %s\n", encoding.characterEncoding());
                indent(sb, level, "/// - semanticType: %s\n", encoding.semanticType());
                indent(sb, level, "/// - encodedOffset: %d\n", typeToken.offset());
                indent(sb, level, "/// - encodedLength: %d\n", typeToken.encodedLength());
                indent(sb, level, "#[inline]\n");
                indent(sb, level, "pub fn %s(&mut self, value: %s) {\n",
                    formatFunctionName(name),
                    rustPrimitiveType);

                // NB: must create variable 'offset' before calling mutable self.get_buf_mut()
                indent(sb, level + 1, "let offset = self.%s;\n", getBufOffset(typeToken));
                indent(sb, level + 1, "self.get_buf_mut().put_%s_at(offset, value);\n", rustPrimitiveType);
                indent(sb, level, "}\n\n");
            }
        }
    }

    static void generateEnumField(
            final StringBuilder sb,
            final int level,
            final Token typeToken,
            final String name
    ) throws IOException {
        final String referencedName = typeToken.referencedName();
        final String enumType = formatStructName(referencedName == null ? typeToken.name() : referencedName);

        indent(sb, level, "/// enum field '%s'\n", name);
        indent(sb, level, "#[pyo3(get)]\n");
        indent(sb, level, "%s: %s,\n\n", toLowerSnakeCase(name), enumType);
    }

    private static void generateEnumEncoder(
        final StringBuilder sb,
        final int level,
        final Token fieldToken,
        final Token typeToken,
        final String name) throws IOException
    {
        final String referencedName = typeToken.referencedName();
        final String enumType = formatStructName(referencedName == null ? typeToken.name() : referencedName);

        if (fieldToken.isConstantEncoding())
        {
            indent(sb, level, "// skipping CONSTANT enum '%s'\n\n", name);
        }
        else
        {
            final Encoding encoding = typeToken.encoding();
            final String rustPrimitiveType = rustTypeName(encoding.primitiveType());

            indent(sb, level, "/// REQUIRED enum\n");
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s(&mut self, value: %s) {\n", formatFunctionName(name), enumType);

            // NB: must create variable 'offset' before calling mutable self.get_buf_mut()
            indent(sb, level + 1, "let offset = self.%s;\n", getBufOffset(typeToken));
            indent(sb, level + 1, "self.get_buf_mut().put_%s_at(offset, value as %1$s)\n", rustPrimitiveType);
            indent(sb, level, "}\n\n");
        }
    }

    static void generateBitSetField(
            final StringBuilder sb,
            final int level,
            final Token bitsetToken,
            final String name
    ) throws IOException {
        final String structTypeName = formatStructName(bitsetToken.applicableTypeName());

        indent(sb, level, "#[pyo3(get)]\n");
        indent(sb, level, "%s: %s,\n\n", toLowerSnakeCase(name), structTypeName);
    }

    private static void generateBitSetEncoder(
        final StringBuilder sb,
        final int level,
        final Token bitsetToken,
        final String name) throws IOException
    {
        final Encoding encoding = bitsetToken.encoding();
        final String rustPrimitiveType = rustTypeName(encoding.primitiveType());
        final String structTypeName = formatStructName(bitsetToken.applicableTypeName());
        indent(sb, level, "#[inline]\n");
        indent(sb, level, "pub fn %s(&mut self, value: %s) {\n", formatFunctionName(name), structTypeName);

        // NB: must create variable 'offset' before calling mutable self.get_buf_mut()
        indent(sb, level + 1, "let offset = self.%s;\n", getBufOffset(bitsetToken));
        indent(sb, level + 1, "self.get_buf_mut().put_%s_at(offset, value.0)\n", rustPrimitiveType);
        indent(sb, level, "}\n\n");
    }

    static void generateCompositeField(
            final StringBuilder sb,
            final int level,
            final Token typeToken,
            final String name
    ) throws IOException {
        final String fieldName = toLowerSnakeCase(name);
        final String structTypeName = formatStructName(name);

        indent(sb, level, "/// composite field '%s'\n", name);
        indent(sb, level, "#[pyo3(get)]\n");
        indent(sb, level, "%s: %s,\n\n", fieldName, structTypeName);
    }

    private static void generateCompositeEncoder(
        final StringBuilder sb,
        final int level,
        final Token typeToken,
        final String name) throws IOException
    {
        final String encoderName = toLowerSnakeCase(encoderName(name));
        final String encoderTypeName = encoderName(formatStructName(typeToken.applicableTypeName()));
        indent(sb, level, "/// COMPOSITE ENCODER\n");
        indent(sb, level, "#[inline]\n");
        indent(sb, level, "pub fn %s(self) -> %2$s<Self> {\n",
            encoderName,
            encoderTypeName);

        // NB: must create variable 'offset' before moving 'self'
        indent(sb, level + 1, "let offset = self.%s;\n", getBufOffset(typeToken));
        indent(sb, level + 1, "%s::default().wrap(self, offset)\n", encoderTypeName);
        indent(sb, level, "}\n\n");
    }

    static void generateGroupField(
            final StringBuilder sb,
            final int level,
            final Token groupToken,
            final String name
    ) throws IOException {
        final String fieldName = toLowerSnakeCase(name);
        final String structTypeName = formatStructName(name);

        indent(sb, level, "/// composite field '%s'\n", name);
        indent(sb, level, "#[pyo3(get)]\n");
        indent(sb, level, "%s: %s,\n\n", fieldName, structTypeName);
    }

    static void generateDecoderFields(
        final StringBuilder sb,
        final List<Token> tokens,
        final int level)
    {
        Generators.forEachField(
            tokens,
            (fieldToken, typeToken) ->
            {
                try
                {
                    final String name = fieldToken.name();
                    final Encoding encoding = typeToken.encoding();

                    switch (typeToken.signal())
                    {
                        case ENCODING:
                            generatePrimitiveDecoder(sb, level, fieldToken, typeToken, name, encoding);
                            break;
                        case BEGIN_ENUM:
                            generateEnumDecoder(sb, level, fieldToken, typeToken, name);
                            break;
                        case BEGIN_SET:
                            generateBitSetDecoder(sb, level, typeToken, name);
                            break;
                        case BEGIN_COMPOSITE:
                            generateCompositeDecoder(sb, level, fieldToken, typeToken, name);
                            break;
                        default:
                            throw new UnsupportedOperationException("Unable to handle: " + typeToken);
                    }
                }
                catch (final IOException ex)
                {
                    throw new UncheckedIOException(ex);
                }
            });
    }

    private static void generateCompositeDecoder(
        final StringBuilder sb,
        final int level,
        final Token fieldToken,
        final Token typeToken,
        final String name) throws IOException
    {
        final String decoderName = toLowerSnakeCase(decoderName(name));
        final String decoderTypeName = decoderName(formatStructName(typeToken.applicableTypeName()));
        indent(sb, level, "/// COMPOSITE DECODER\n");
        indent(sb, level, "#[inline]\n");
        if (fieldToken.version() > 0)
        {
            indent(sb, level, "pub fn %s(self) -> Either<Self, %2$s<Self>> {\n",
                decoderName,
                decoderTypeName);

            indent(sb, level + 1, "if self.acting_version < %d {\n", fieldToken.version());
            indent(sb, level + 2, "return Either::Left(self);\n");
            indent(sb, level + 1, "}\n\n");

            indent(sb, level + 1, "let offset = self.%s;\n", getBufOffset(fieldToken));
            indent(sb, level + 1, "Either::Right(%s::default().wrap(self, offset))\n",
                decoderTypeName);
        }
        else
        {
            indent(sb, level, "pub fn %s(self) -> %2$s<Self> {\n",
                decoderName,
                decoderTypeName);

            indent(sb, level + 1, "let offset = self.%s;\n", getBufOffset(fieldToken));
            indent(sb, level + 1, "%s::default().wrap(self, offset)\n", decoderTypeName);
        }
        indent(sb, level, "}\n\n");
    }

    private static void generateBitSetDecoder(
        final StringBuilder sb,
        final int level,
        final Token bitsetToken,
        final String name) throws IOException
    {
        final Encoding encoding = bitsetToken.encoding();
        final String rustPrimitiveType = rustTypeName(encoding.primitiveType());
        final String structTypeName = formatStructName(bitsetToken.applicableTypeName());
        indent(sb, level, "#[inline]\n");
        indent(sb, level, "pub fn %s(&self) -> %s {\n", formatFunctionName(name), structTypeName);

        if (bitsetToken.version() > 0)
        {
            indent(sb, level + 1, "if self.acting_version < %d {\n", bitsetToken.version());
            indent(sb, level + 2, "return %s::default();\n", structTypeName);
            indent(sb, level + 1, "}\n\n");
        }

        indent(sb, level + 1, "%s::new(self.get_buf().get_%s_at(self.%s))\n",
            structTypeName,
            rustPrimitiveType,
            getBufOffset(bitsetToken));
        indent(sb, level, "}\n\n");
    }

    private static void generatePrimitiveDecoder(
        final StringBuilder sb,
        final int level,
        final Token fieldToken,
        final Token typeToken,
        final String name,
        final Encoding encoding) throws IOException
    {
        if (typeToken.arrayLength() > 1)
        {
            generatePrimitiveArrayDecoder(sb, level, fieldToken, typeToken, name, encoding);
        }
        else if (encoding.presence() == Encoding.Presence.CONSTANT)
        {
            generatePrimitiveConstantDecoder(sb, level, name, encoding);
        }
        else if (encoding.presence() == Encoding.Presence.OPTIONAL)
        {
            generatePrimitiveOptionalDecoder(sb, level, fieldToken, name, encoding);
        }
        else
        {
            generatePrimitiveRequiredDecoder(sb, level, fieldToken, name, encoding);
        }
    }

    private static void generatePrimitiveArrayDecoder(
        final StringBuilder sb,
        final int level,
        final Token fieldToken,
        final Token typeToken,
        final String name,
        final Encoding encoding) throws IOException
    {
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String rustPrimitiveType = rustTypeName(primitiveType);

        final int arrayLength = typeToken.arrayLength();
        assert arrayLength > 1;

        indent(sb, level, "#[inline]\n");
        indent(sb, level, "pub fn %s(&self) -> [%s; %d] {\n",
            formatFunctionName(name),
            rustPrimitiveType,
            arrayLength);

        if (fieldToken.version() > 0)
        {
            indent(sb, level + 1, "if self.acting_version < %d {\n", fieldToken.version());
            indent(sb, level + 2, "return [%s, %d];\n", encoding.applicableNullValue(), arrayLength);
            indent(sb, level + 1, "}\n\n");
        }

        indent(sb, level + 1, "let buf = self.get_buf();\n");
        indent(sb, level + 1, "[\n");
        for (int i = 0; i < arrayLength; i++)
        {
            if (i == 0)
            {
                indent(sb, level + 2, "buf.get_%s_at(self.%s),\n",
                    rustPrimitiveType,
                    getBufOffset(typeToken));
            }
            else
            {
                indent(sb, level + 2, "buf.get_%s_at(self.%s + %d),\n",
                    rustPrimitiveType,
                    getBufOffset(typeToken),
                    i * primitiveType.size());
            }
        }
        indent(sb, level + 1, "]\n");
        indent(sb, level, "}\n\n");
    }

    private static void generatePrimitiveConstantDecoder(
        final StringBuilder sb,
        final int level,
        final String name,
        final Encoding encoding) throws IOException
    {
        assert encoding.presence() == Encoding.Presence.CONSTANT;
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String rustPrimitiveType = rustTypeName(primitiveType);
        final String characterEncoding = encoding.characterEncoding();

        indent(sb, level, "/// CONSTANT \n");
        final String rawConstValue = encoding.constValue().toString();
        if (characterEncoding != null)
        {
            indent(sb, level, "/// characterEncoding: '%s'\n", characterEncoding);
            indent(sb, level, "#[inline]\n");

            if (JavaUtil.isAsciiEncoding(characterEncoding))
            {
                indent(sb, level, "pub fn %s(&self) -> &'static [u8] {\n",
                    formatFunctionName(name));
                indent(sb, level + 1, "b\"%s\"\n", rawConstValue);
            }
            else if (JavaUtil.isUtf8Encoding(characterEncoding))
            {
                indent(sb, level, "pub fn %s(&self) -> &'static str {\n", formatFunctionName(name));
                indent(sb, level + 1, "\"%s\"\n", rawConstValue);
            }
            else
            {
                throw new IllegalArgumentException("Unsupported encoding: " + characterEncoding);
            }

            indent(sb, level, "}\n\n");
        }
        else
        {
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s(&self) -> %s {\n",
                formatFunctionName(name),
                rustPrimitiveType);
            indent(sb, level + 1, "%s\n", rawConstValue);
            indent(sb, level, "}\n\n");
        }
    }

    private static void generatePrimitiveOptionalDecoder(
        final StringBuilder sb,
        final int level,
        final Token fieldToken,
        final String name,
        final Encoding encoding) throws IOException
    {
        assert encoding.presence() == Encoding.Presence.OPTIONAL;
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String rustPrimitiveType = rustTypeName(primitiveType);
        final String characterEncoding = encoding.characterEncoding();
        indent(sb, level, "/// primitive field - '%s' { null_value: '%s' }\n",
            encoding.presence(), encoding.applicableNullValue());

        if (characterEncoding != null)
        {
            // ASCII and UTF-8
            indent(sb, level, "/// characterEncoding: '%s'\n", characterEncoding);
        }

        indent(sb, level, "#[inline]\n");
        indent(sb, level, "pub fn %s(&self) -> Option<%s> {\n",
            formatFunctionName(name),
            rustPrimitiveType);

        if (fieldToken.version() > 0)
        {
            indent(sb, level + 1, "if self.acting_version < %d {\n", fieldToken.version());
            indent(sb, level + 2, "return None;\n");
            indent(sb, level + 1, "}\n\n");
        }

        indent(sb, level + 1, "let value = self.get_buf().get_%s_at(self.%s);\n",
            rustPrimitiveType,
            getBufOffset(fieldToken));


        final String literal = generateRustLiteral(primitiveType, encoding.applicableNullValue().toString());
        if (literal.endsWith("::NAN"))
        {
            indent(sb, level + 1, "if value.is_nan() {\n");
        }
        else
        {
            indent(sb, level + 1, "if value == %s {\n", literal);
        }

        indent(sb, level + 2, "None\n");
        indent(sb, level + 1, "} else {\n");
        indent(sb, level + 2, "Some(value)\n");
        indent(sb, level + 1, "}\n");
        indent(sb, level, "}\n\n");
    }

    private static void generatePrimitiveRequiredDecoder(
        final StringBuilder sb,
        final int level,
        final Token fieldToken,
        final String name,
        final Encoding encoding) throws IOException
    {
        assert encoding.presence() == Encoding.Presence.REQUIRED;
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String rustPrimitiveType = rustTypeName(primitiveType);
        final String characterEncoding = encoding.characterEncoding();
        indent(sb, level, "/// primitive field - '%s'\n", encoding.presence());

        if (characterEncoding != null)
        {
            // ASCII and UTF-8
            indent(sb, level, "/// characterEncoding: '%s'\n", characterEncoding);
        }

        indent(sb, level, "#[inline]\n");
        indent(sb, level, "pub fn %s(&self) -> %s {\n",
            formatFunctionName(name),
            rustPrimitiveType);

        if (fieldToken.version() > 0)
        {
            indent(sb, level + 1, "if self.acting_version < %d {\n", fieldToken.version());
            indent(sb, level + 2, "return %s;\n",
                generateRustLiteral(encoding.primitiveType(), encoding.applicableNullValue().toString()));
            indent(sb, level + 1, "}\n\n");
        }

        indent(sb, level + 1, "self.get_buf().get_%s_at(self.%s)\n",
            rustPrimitiveType,
            getBufOffset(fieldToken));
        indent(sb, level, "}\n\n");

    }

    private static void generateEnumDecoder(
        final StringBuilder sb,
        final int level,
        final Token fieldToken,
        final Token typeToken,
        final String name) throws IOException
    {
        final String referencedName = typeToken.referencedName();
        final String enumType = formatStructName(referencedName == null ? typeToken.name() : referencedName);

        if (fieldToken.isConstantEncoding())
        {
            indent(sb, level, "/// CONSTANT enum\n");
            final Encoding encoding = fieldToken.encoding();
            final String rawConstValueName = encoding.constValue().toString();
            final int indexOfDot = rawConstValueName.indexOf('.');
            final String constValueName = -1 == indexOfDot ?
                rawConstValueName :
                rawConstValueName.substring(indexOfDot + 1);

            final String constantRustExpression = enumType + "::" + constValueName;
            appendConstAccessor(sb, name, enumType, constantRustExpression, level);
        }
        else
        {
            final Encoding encoding = typeToken.encoding();
            final String rustPrimitiveType = rustTypeName(encoding.primitiveType());

            indent(sb, level, "/// REQUIRED enum\n");
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s(&self) -> %s {\n", formatFunctionName(name), enumType);

            if (fieldToken.version() > 0)
            {
                indent(sb, level + 1, "if self.acting_version < %d {\n", fieldToken.version());
                indent(sb, level + 2, "return %s::default();\n", enumType);
                indent(sb, level + 1, "}\n\n");
            }

            indent(sb, level + 1, "self.get_buf().get_%s_at(self.%s).into()\n",
                rustPrimitiveType,
                getBufOffset(typeToken));
            indent(sb, level, "}\n\n");
        }
    }

    static void generateDecoderGroups(
        final StringBuilder sb,
        final List<Token> tokens,
        final int level,
        final ParentDef parentDef) throws IOException
    {
        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token groupToken = tokens.get(i);
            if (groupToken.signal() != Signal.BEGIN_GROUP)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_GROUP: token=" + groupToken);
            }

            ++i;
            final int index = i;
            final int groupHeaderTokenCount = tokens.get(i).componentTokenCount();
            i += groupHeaderTokenCount;

            final List<Token> fields = new ArrayList<>();
            i = collectFields(tokens, i, fields);

            final List<Token> groups = new ArrayList<>();
            i = collectGroups(tokens, i, groups);

            final List<Token> varData = new ArrayList<>();
            i = collectVarData(tokens, i, varData);

            final String groupName = decoderName(formatStructName(groupToken.name()));
            indent(sb, level, "/// GROUP DECODER\n");
            assert 4 == groupHeaderTokenCount;

            indent(sb, level, "#[inline]\n");
            if (groupToken.version() > 0)
            {
                indent(sb, level, "pub fn %s(self) -> Option<%2$s<Self>> {\n",
                    formatFunctionName(groupName), groupName);

                indent(sb, level + 1, "if self.acting_version < %d {\n", groupToken.version());
                indent(sb, level + 2, "return None;\n");
                indent(sb, level + 1, "}\n\n");

                indent(sb, level + 1, "let acting_version = self.acting_version;\n");
                indent(sb, level + 1, "Some(%s::default().wrap(self, acting_version as usize))\n",
                    groupName);
            }
            else
            {
                indent(sb, level, "pub fn %s(self) -> %2$s<Self> {\n",
                    formatFunctionName(groupName), groupName);

                indent(sb, level + 1, "let acting_version = self.acting_version;\n");
                indent(sb, level + 1, "%s::default().wrap(self, acting_version as usize)\n",
                    groupName);
            }
            indent(sb, level, "}\n\n");

            final SubGroup subGroup = parentDef.addSubGroup(groupName, level, groupToken);
            subGroup.generateDecoder(tokens, fields, groups, varData, index);
        }
    }

    static void generateDecoderVarData(
        final StringBuilder sb,
        final List<Token> tokens,
        final int level,
        final boolean isSubGroup) throws IOException
    {
        for (int i = 0, size = tokens.size(); i < size; )
        {
            final Token varDataToken = tokens.get(i);
            if (varDataToken.signal() != Signal.BEGIN_VAR_DATA)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_VAR_DATA: token=" + varDataToken);
            }

            final String characterEncoding = characterEncoding(tokens.get(i + 3).encoding());
            final String propertyName = toLowerSnakeCase(varDataToken.name());
            final Token lengthToken = tokens.get(i + 2);
            final Encoding lengthEncoding = lengthToken.encoding();
            final PrimitiveType lengthType = lengthEncoding.primitiveType();

            indent(sb, level, "/// VAR_DATA DECODER - character encoding: '%s'\n", characterEncoding);
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s_decoder(&mut self) -> (usize, usize) {\n", propertyName);

            if (isSubGroup)
            {
                if (varDataToken.version() > 0)
                {
                    indent(sb, level + 1, "if self.acting_version < %d {\n", varDataToken.version());
                    indent(sb, level + 2, "return (self.parent.as_ref().unwrap().get_limit(), 0);\n");
                    indent(sb, level + 1, "}\n\n");
                }

                indent(sb, level + 1, "let offset = self.parent.as_ref().expect(\"parent missing\").get_limit();\n");
                indent(sb, level + 1, "let data_length = self.get_buf().get_%s_at(offset) as usize;\n",
                    rustTypeName(lengthType));
                indent(sb, level + 1, "self.parent.as_mut().unwrap().set_limit(offset + %d + data_length);\n",
                    lengthType.size());
            }
            else
            {
                if (varDataToken.version() > 0)
                {
                    indent(sb, level + 1, "if self.acting_version < %d {\n", varDataToken.version());
                    indent(sb, level + 2, "return (self.get_limit(), 0);\n");
                    indent(sb, level + 1, "}\n\n");
                }

                indent(sb, level + 1, "let offset = self.get_limit();\n");
                indent(sb, level + 1, "let data_length = self.get_buf().get_%s_at(offset) as usize;\n",
                    rustTypeName(lengthType));
                indent(sb, level + 1, "self.set_limit(offset + %d + data_length);\n", lengthType.size());
            }
            indent(sb, level + 1, "(offset + %d, data_length)\n", lengthType.size());
            indent(sb, level, "}\n\n");

            // function to return slice form given coord
            indent(sb, level, "#[inline]\n");
            indent(sb, level, "pub fn %s_slice(&self, coordinates: (usize, usize)) -> &[u8] {\n", propertyName);

            if (varDataToken.version() > 0)
            {
                indent(sb, level + 1, "if self.acting_version < %d {\n", varDataToken.version());
                indent(sb, level + 2, "return &[] as &[u8];\n");
                indent(sb, level + 1, "}\n\n");
            }

            indent(sb, level + 1, "debug_assert!(self.get_limit() >= coordinates.0 + coordinates.1);\n");
            indent(sb, level + 1, "self.get_buf().get_slice_at(coordinates.0, coordinates.1)\n");
            indent(sb, level, "}\n\n");

            i += varDataToken.componentTokenCount();
        }
    }

    private static void generateBitSets(
        final Ir ir,
        final RustPythonOutputManager outputManager) throws IOException
    {
        for (final List<Token> tokens : ir.types())
        {
            if (!tokens.isEmpty() && tokens.get(0).signal() == BEGIN_SET)
            {
                final Token beginToken = tokens.get(0);
                final String bitSetType = formatStructName(beginToken.applicableTypeName());

                try (Writer out = outputManager.createOutput(bitSetType))
                {
                    generateSingleBitSet(bitSetType, tokens, out);
                }
            }
        }
    }

    private static void generateSingleBitSet(
        final String bitSetType,
        final List<Token> tokens,
        final Appendable writer) throws IOException
    {
        final Token beginToken = tokens.get(0);
        final String rustPrimitiveType = rustTypeName(beginToken.encoding().primitiveType());
        indent(writer, 0, "use crate::*;\n\n");
        indent(writer, 0, "#[derive(Default, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]\n");
        indent(writer, 0, "#[pyclass]\n");
        indent(writer, 0, "pub struct %s(pub %s);\n", bitSetType, rustPrimitiveType);
        indent(writer, 0, "impl %s {\n", bitSetType);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "pub fn new(value: %s) -> Self {\n", rustPrimitiveType);
        indent(writer, 2, "%s(value)\n", bitSetType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "pub fn clear(&mut self) -> &mut Self {\n");
        indent(writer, 2, "self.0 = 0;\n");
        indent(writer, 2, "self\n");
        indent(writer, 1, "}\n");

        for (final Token token : tokens)
        {
            if (Signal.CHOICE != token.signal())
            {
                continue;
            }

            final String choiceName = formatFunctionName(token.name());
            final Encoding encoding = token.encoding();
            final String choiceBitIndex = encoding.constValue().toString();

            indent(writer, 0, "\n");
            indent(writer, 1, "#[inline]\n");
            indent(writer, 1, "pub fn get_%s(&self) -> bool {\n", choiceName);
            indent(writer, 2, "0 != self.0 & (1 << %s)\n", choiceBitIndex);
            indent(writer, 1, "}\n\n");

            indent(writer, 1, "#[inline]\n");
            indent(writer, 1, "pub fn set_%s(&mut self, value: bool) -> &mut Self {\n", choiceName);
            indent(writer, 2, "self.0 = if value {\n");
            indent(writer, 3, "self.0 | (1 << %s)\n", choiceBitIndex);
            indent(writer, 2, "} else {\n");
            indent(writer, 3, "self.0 & !(1 << %s)\n", choiceBitIndex);
            indent(writer, 2, "};\n");
            indent(writer, 2, "self\n");
            indent(writer, 1, "}\n");
        }
        indent(writer, 0, "}\n");

        indent(writer, 0, "impl core::fmt::Debug for %s {\n", bitSetType);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn fmt(&self, fmt: &mut core::fmt::Formatter) -> core::fmt::Result {\n");
        indent(writer, 2, "write!(fmt, \"%s[", bitSetType);

        final StringBuilder builder = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
        boolean comma = false;
        for (final Token token : tokens)
        {
            if (Signal.CHOICE != token.signal())
            {
                continue;
            }

            final String choiceName = formatFunctionName(token.name());
            final String choiceBitIndex = token.encoding().constValue().toString();

            if (comma)
            {
                builder.append(",");
            }

            builder.append(choiceName).append("(").append(choiceBitIndex).append(")={}");
            arguments.append("self.get_").append(choiceName).append("(),");
            comma = true;
        }

        writer.append(builder.toString()).append("]\",\n");
        indent(writer, 3, arguments + ")\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n");
    }

    static void appendImplEncoderTrait(
        final Appendable out,
        final String typeName) throws IOException
    {
        indent(out, 1, "impl<%s> %s for %s {\n", BUF_LIFETIME, withLifetime("Writer"), withLifetime(typeName));
        indent(out, 2, "#[inline]\n");
        indent(out, 2, "fn get_buf_mut(&mut self) -> &mut WriteBuf<'a> {\n");
        indent(out, 3, "&mut self.buf\n");
        indent(out, 2, "}\n");
        indent(out, 1, "}\n\n");

        indent(out, 1, "impl<%s> %s for %s {\n", BUF_LIFETIME, withLifetime("Encoder"), withLifetime(typeName));
        indent(out, 2, "#[inline]\n");
        indent(out, 2, "fn get_limit(&self) -> usize {\n");
        indent(out, 3, "self.limit\n");
        indent(out, 2, "}\n\n");

        indent(out, 2, "#[inline]\n");
        indent(out, 2, "fn set_limit(&mut self, limit: usize) {\n");
        indent(out, 3, "self.limit = limit;\n");
        indent(out, 2, "}\n");
        indent(out, 1, "}\n\n");
    }

    static void appendImplDecoderTrait(
        final Appendable out,
        final String typeName) throws IOException
    {
        indent(out, 1, "impl<%s> Reader for %s {\n", BUF_LIFETIME, withLifetime(typeName));
        indent(out, 2, "#[inline]\n");
        indent(out, 2, "fn get_buf(&self) -> &ReadBuf<'a> {\n");
        indent(out, 3, "&self.buf\n");
        indent(out, 2, "}\n");
        indent(out, 1, "}\n\n");

        indent(out, 1, "impl<%s> Decoder for %s {\n", BUF_LIFETIME, withLifetime(typeName));
        indent(out, 2, "#[inline]\n");
        indent(out, 2, "fn get_limit(&self) -> usize {\n");
        indent(out, 3, "self.limit\n");
        indent(out, 2, "}\n\n");

        indent(out, 2, "#[inline]\n");
        indent(out, 2, "fn set_limit(&mut self, limit: usize) {\n");
        indent(out, 3, "self.limit = limit;\n");
        indent(out, 2, "}\n");
        indent(out, 1, "}\n\n");
    }

    private static void generateEnums(
        final Ir ir,
        final RustPythonOutputManager outputManager) throws IOException
    {
        final Set<String> enumTypeNames = new HashSet<>();
        for (final List<Token> tokens : ir.types())
        {
            if (tokens.isEmpty())
            {
                continue;
            }

            final Token beginToken = tokens.get(0);
            if (beginToken.signal() != BEGIN_ENUM)
            {
                continue;
            }

            final String typeName = beginToken.applicableTypeName();
            if (!enumTypeNames.add(typeName))
            {
                continue;
            }

            try (Writer out = outputManager.createOutput(typeName))
            {
                generateEnum(tokens, out);
            }
        }
    }

    private static void generateEnum(
        final List<Token> enumTokens,
        final Appendable writer) throws IOException
    {
        final String originalEnumName = enumTokens.get(0).applicableTypeName();
        final String enumRustName = formatStructName(originalEnumName);
        final List<Token> messageBody = enumTokens.subList(1, enumTokens.size() - 1);
        if (messageBody.isEmpty())
        {
            throw new IllegalArgumentException("No valid values provided for enum " + originalEnumName);
        }

        indent(writer, 0, "use crate::*;\n\n");
        indent(writer, 0, "#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]\n");
        final String primitiveType = rustTypeName(messageBody.get(0).encoding().primitiveType());
        indent(writer, 0, "#[repr(%s)]\n", primitiveType);
        indent(writer, 0, "#[pyclass]\n");
        indent(writer, 0, "pub enum %s {\n", enumRustName);

        for (final Token token : messageBody)
        {
            final Encoding encoding = token.encoding();
            final String literal = generateRustLiteral(encoding.primitiveType(), encoding.constValue().toString());
            indent(writer, 1, "%s = %s, \n", token.name(), literal);
        }

        // null value
        {
            final Encoding encoding = messageBody.get(0).encoding();
            final CharSequence nullVal = generateRustLiteral(encoding.primitiveType(),
                encoding.applicableNullValue().toString());
            indent(writer, 1, "NullVal = %s, \n", nullVal);
        }
        indent(writer, 0, "}\n");

        // Default implementation to support Default in other structs
        indent(writer, 0, "impl Default for %s {\n", enumRustName);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn default() -> Self { %s::%s }\n", enumRustName, "NullVal");
        indent(writer, 0, "}\n");

        // From impl
        indent(writer, 0, "impl From<%s> for %s {\n", primitiveType, enumRustName);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn from(v: %s) -> Self {\n", primitiveType);
        indent(writer, 2, "match v {\n");

        for (final Token token : messageBody)
        {
            final Encoding encoding = token.encoding();
            final String literal = generateRustLiteral(encoding.primitiveType(), encoding.constValue().toString());
            indent(writer, 3, "%s => Self::%s, \n", literal, token.name());
        }

        // default => NullVal
        indent(writer, 3, "_ => Self::NullVal,\n");
        indent(writer, 2, "}\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n");
    }

    private static void generateComposites(
        final Ir ir,
        final RustPythonOutputManager outputManager) throws IOException
    {
        for (final List<Token> tokens : ir.types())
        {
            if (!tokens.isEmpty() && tokens.get(0).signal() == Signal.BEGIN_COMPOSITE)
            {
                generateComposite(tokens, outputManager);
            }
        }
    }

    private static void generateComposite(
        final List<Token> tokens,
        final RustPythonOutputManager outputManager) throws IOException
    {
        final Token token = tokens.get(0);
        final String compositeName = token.applicableTypeName();
        final String compositeModName = codecModName(compositeName);

        try (Writer out = outputManager.createOutput(compositeModName))
        {
            indent(out, 0, "use pyo3::types::PyType;\n");
            indent(out, 0, "use crate::*;\n\n");

            indent(out, 0, "pub use encoder::*;\n");
            indent(out, 0, "pub use decoder::*;\n\n");

            final int encodedLength = tokens.get(0).encodedLength();
            if (encodedLength > 0)
            {
                indent(out, 0, "pub const ENCODED_LENGTH: usize = %d;\n\n", encodedLength);
            }

            generateCompositeStruct(tokens, formatStructName(compositeName), out);
            indent(out, 0, "\n");
            generateCompositeEncoder(tokens, encoderName(compositeName), out);
            indent(out, 0, "\n");
            generateCompositeDecoder(tokens, decoderName(compositeName), out);
        }
    }

    static void appendImplWriterForComposite(
        final Appendable out,
        final int level,
        final String name) throws IOException
    {
        // impl Decoder...
        indent(out, level, "impl<'a, P> Writer<'a> for %s<P> where P: Writer<'a> + Default {\n", name);
        indent(out, level + 1, "#[inline]\n");
        indent(out, level + 1, "fn get_buf_mut(&mut self) -> &mut WriteBuf<'a> {\n");
        indent(out, level + 2, "if let Some(parent) = self.parent.as_mut() {\n");
        indent(out, level + 3, "parent.get_buf_mut()\n");
        indent(out, level + 2, "} else {\n");
        indent(out, level + 3, "panic!(\"parent was None\")\n");
        indent(out, level + 2, "}\n");
        indent(out, level + 1, "}\n");
        indent(out, level, "}\n\n");
    }

    static void appendImplEncoderForComposite(
        final Appendable out,
        final int level,
        final String name) throws IOException
    {
        appendImplWriterForComposite(out, level, name);

        // impl Encoder...
        indent(out, level, "impl<'a, P> Encoder<'a> for %s<P> where P: Encoder<'a> + Default {\n", name);
        indent(out, level + 1, "#[inline]\n");
        indent(out, level + 1, "fn get_limit(&self) -> usize {\n");
        indent(out, level + 2, "self.parent.as_ref().expect(\"parent missing\").get_limit()\n");
        indent(out, level + 1, "}\n\n");

        indent(out, level + 1, "#[inline]\n");
        indent(out, level + 1, "fn set_limit(&mut self, limit: usize) {\n");
        indent(out, level + 2, "self.parent.as_mut().expect(\"parent missing\").set_limit(limit);\n");
        indent(out, level + 1, "}\n");
        indent(out, level, "}\n\n");
    }

    static void appendImplReaderForComposite(
        final Appendable out,
        final int level,
        final String name) throws IOException
    {
        // impl Reader...
        indent(out, level, "impl<P> Reader for %s<P> where P: Reader + Default {\n", name);
        indent(out, level + 1, "#[inline]\n");
        indent(out, level + 1, "fn get_buf(&self) -> &ReadBuf {\n");
        indent(out, level + 2, "self.parent.as_ref().expect(\"parent missing\").get_buf()\n");
        indent(out, level + 1, "}\n");
        indent(out, level, "}\n\n");
    }

    static void appendImplDecoderForComposite(
        final Appendable out,
        final int level,
        final String name) throws IOException
    {
        appendImplReaderForComposite(out, level, name);

        // impl Decoder...
        indent(out, level, "impl<P> Decoder for %s<P> where P: Decoder + Default {\n", name);
        indent(out, level + 1, "#[inline]\n");
        indent(out, level + 1, "fn get_limit(&self) -> usize {\n");
        indent(out, level + 2, "self.parent.as_ref().expect(\"parent missing\").get_limit()\n");
        indent(out, level + 1, "}\n\n");

        indent(out, level + 1, "#[inline]\n");
        indent(out, level + 1, "fn set_limit(&mut self, limit: usize) {\n");
        indent(out, level + 2, "self.parent.as_mut().expect(\"parent missing\").set_limit(limit);\n");
        indent(out, level + 1, "}\n");
        indent(out, level, "}\n\n");
    }

    private static void appendCompositeFromBuf(
            final String structName,
            final Writer out
    ) throws IOException
    {
        indent(out, 1, "#[classmethod]\n");
        indent(out, 1, "pub fn from_buf(_cls: &PyType, buf: &[u8], offset: usize) -> Self {\n", structName);
        indent(out, 2, "let read_buf = ReadBuf::new(buf);\n");
        indent(out, 2, "let decoder = %s::default().wrap(read_buf, offset);\n\n", decoderName(structName));
        indent(out, 2, "Self::read_from_decoder(decoder)\n");
        indent(out, 1, "}\n\n");
    }

    private static void appendCompositeToBuf(
            final String structName,
            final Writer out
    ) throws IOException
    {
        indent(out, 1, "pub fn to_buf(&self) -> Vec<u8> {\n", structName);
        indent(out, 2, "let mut buf = vec![0u8; ENCODED_LENGTH];\n");
        indent(out, 2, "let write_buf = WriteBuf::new(&mut buf);\n");
        indent(out, 2, "let encoder = %s::default().wrap(write_buf, 0);\n", encoderName(structName));
        indent(out, 2, "self.write_to_encoder(encoder);\n\n", encoderName(structName));
        indent(out, 2, "buf\n");
        indent(out, 1, "}\n\n");
    }

    private static void appendCompositeNew(
            final List<Token> tokens,
            final Writer out
    ) throws IOException
    {
        indent(out, 1, "#[new]\n");
        indent(out, 1, "pub fn new(\n");
        for (int i = 1, end = tokens.size() - 1; i < end; ) {
            final Token encodingToken = tokens.get(i);
            switch (encodingToken.signal()) {
                case ENCODING -> {
                    if (encodingToken.arrayLength() > 1)
                        indent(out, 2, "%s: [%s; %d],\n", toLowerSnakeCase(encodingToken.name()), rustTypeName(encodingToken.encoding().primitiveType()), encodingToken.arrayLength());
                    else if (!encodingToken.isConstantEncoding())
                        indent(out, 2, "%s: %s,\n", toLowerSnakeCase(encodingToken.name()), rustTypeName(encodingToken.encoding().primitiveType()));
                }
                case BEGIN_ENUM -> {
                    final String referencedName = encodingToken.referencedName();
                    final String enumType = formatStructName(referencedName == null ? encodingToken.name() : referencedName);
                    indent(out, 2, "%s: %s,\n", formatFunctionName(encodingToken.name()), enumType);
                }
                case BEGIN_SET -> {
                    final String structTypeName = formatStructName(encodingToken.applicableTypeName());
                    indent(out, 2, "%s: %s,\n", toLowerSnakeCase(encodingToken.name()), structTypeName);
                }
                case BEGIN_COMPOSITE -> {
                    final String structTypeName = formatStructName(encodingToken.name());
                    indent(out, 2, "%s: %s,\n", toLowerSnakeCase(encodingToken.name()), structTypeName);
                }
                default -> {}
            }
            i += encodingToken.componentTokenCount();
        }

        indent(out, 1, ") -> Self {\n");
        indent(out, 2, "Self {\n");
        for (int i = 1, end = tokens.size() - 1; i < end; ) {
            final Token encodingToken = tokens.get(i);
            if (!encodingToken.isConstantEncoding())
                indent(out, 3, "%s,\n", toLowerSnakeCase(encodingToken.name()));
            i += encodingToken.componentTokenCount();
        }
        indent(out, 2, "}\n");
        indent(out, 1, "}\n\n");
    }

    private static void appendCompositePythonImpl(
            final List<Token> tokens,
            final String structName,
            final Writer out
    ) throws IOException
    {
        indent(out, 0, "#[pymethods]\n");
        indent(out, 0, "impl %s {\n", structName);
        appendCompositeNew(tokens, out);
        appendCompositeFromBuf(structName, out);
        appendCompositeToBuf(structName, out);
        indent(out, 0, "}\n\n");
    }

    private static void appendCompositeWriteToEncoder(
            final List<Token> tokens,
            final String structName,
            final Writer out
    ) throws IOException
    {
        indent(out, 1, "pub fn write_to_encoder<%s, P: Writer<%1$s> + Default>(&self, mut encoder: %2$s<P>) {\n", BUF_LIFETIME, encoderName(structName));
        for (int i = 1, end = tokens.size() - 1; i < end; ) {
            final Token encodingToken = tokens.get(i);
            if (encodingToken.isConstantEncoding())
            {
                i += encodingToken.componentTokenCount();
                continue;
            }

            if (Objects.requireNonNull(encodingToken.signal()) == Signal.BEGIN_COMPOSITE)
                indent(out, 2, "self.%s.write_to_encoder(encoder.%1$s_encoder());\n", formatFunctionName(encodingToken.name()));
            else
                indent(out, 2, "encoder.%s(self.%1$s);\n", formatFunctionName(encodingToken.name()));

            i += encodingToken.componentTokenCount();
        }
        indent(out, 1, "}\n\n");
    }

    private static void appendCompositeReadFromEncoder(
            final List<Token> tokens,
            final String structName,
            final Writer out
    ) throws IOException
    {
        indent(out, 1, "pub fn read_from_decoder<P: Reader + Default>(decoder: %s<P>) -> Self {\n", decoderName(structName));
        indent(out, 2, "Self {\n");
        for (int i = 1, end = tokens.size() - 1; i < end; ) {
            final Token encodingToken = tokens.get(i);
            if (encodingToken.isConstantEncoding())
            {
                i += encodingToken.componentTokenCount();
                continue;
            }

            if (Objects.requireNonNull(encodingToken.signal()) == Signal.BEGIN_COMPOSITE)
                indent(out, 3, "%s: %s::read_from_decoder(decoder.%1$s_decoder()),\n", toLowerSnakeCase(encodingToken.name()), formatStructName(encodingToken.name()));
            else
                indent(out, 3, "%s: decoder.%1$s(),\n", toLowerSnakeCase(encodingToken.name()));

            i += encodingToken.componentTokenCount();
        }
        indent(out, 2, "}\n");
        indent(out, 1, "}\n");
    }

    private static void appendCompositeRustImpl(
            final List<Token> tokens,
            final String structName,
            final Writer out
    ) throws IOException
    {
        indent(out, 0, "impl %s {\n", structName);
        appendCompositeWriteToEncoder(tokens, structName, out);
        appendCompositeReadFromEncoder(tokens, structName, out);
        indent(out, 0, "}\n\n");
    }


    private static void generateCompositeStruct(
            final List<Token> tokens,
            final String structName,
            final Writer out
    ) throws IOException
    {
        // define struct...
        indent(out, 0, "#[derive(Debug, Default, Clone)]\n");
        indent(out, 0, "#[pyclass]\n");
        indent(out, 0, "pub struct %s {\n", structName);
        for (int i = 1, end = tokens.size() - 1; i < end; ) {
            final Token encodingToken = tokens.get(i);
            final StringBuilder sb = new StringBuilder();

            switch (encodingToken.signal()) {
                case ENCODING -> generatePrimitiveField(sb, 1, encodingToken, encodingToken.name());
                case BEGIN_ENUM -> generateEnumField(sb, 1, encodingToken, encodingToken.name());
                case BEGIN_SET -> generateBitSetField(sb, 1, encodingToken, encodingToken.name());
                case BEGIN_COMPOSITE -> generateCompositeField(sb, 1, encodingToken, encodingToken.name());
                default -> {}
            }

            out.append(sb);
            i += encodingToken.componentTokenCount();
        }
        indent(out, 0, "}\n\n");

        // PYTHON IMPL
        appendCompositePythonImpl(tokens, structName, out);

        // RUST IMPL
        appendCompositeRustImpl(tokens, structName, out);
    }

    private static void generateCompositeEncoder(
        final List<Token> tokens,
        final String encoderName,
        final Writer out
    ) throws IOException
    {
        indent(out, 0, "pub mod encoder {\n");
        indent(out, 1, "use super::*;\n\n");

        // define struct...
        indent(out, 1, "#[derive(Debug, Default)]\n");
        indent(out, 1, "pub struct %s<P> {\n", encoderName);
        indent(out, 2, "parent: Option<P>,\n");
        indent(out, 2, "offset: usize,\n");
        indent(out, 1, "}\n\n");

        appendImplWriterForComposite(out, 1, encoderName);

        // impl<'a> start
        indent(out, 1, "impl<'a, P> %s<P> where P: Writer<'a> + Default {\n", encoderName);
        indent(out, 2, "pub fn wrap(mut self, parent: P, offset: usize) -> Self {\n");
        indent(out, 3, "self.parent = Some(parent);\n");
        indent(out, 3, "self.offset = offset;\n");
        indent(out, 3, "self\n");
        indent(out, 2, "}\n\n");

        // parent fn...
        indent(out, 2, "#[inline]\n");
        indent(out, 2, "pub fn parent(&mut self) -> SbeResult<P> {\n");
        indent(out, 3, "self.parent.take().ok_or(SbeErr::ParentNotSet)\n");
        indent(out, 2, "}\n\n");

        for (int i = 1, end = tokens.size() - 1; i < end; )
        {
            final Token encodingToken = tokens.get(i);
            final StringBuilder sb = new StringBuilder();

            switch (encodingToken.signal())
            {
                case ENCODING:
                    generatePrimitiveEncoder(sb, 2, encodingToken, encodingToken.name());
                    break;
                case BEGIN_ENUM:
                    generateEnumEncoder(sb, 2, encodingToken, encodingToken, encodingToken.name());
                    break;
                case BEGIN_SET:
                    generateBitSetEncoder(sb, 2, encodingToken, encodingToken.name());
                    break;
                case BEGIN_COMPOSITE:
                    generateCompositeEncoder(sb, 2, encodingToken, encodingToken.name());
                    break;
                default:
                    break;
            }

            out.append(sb);
            i += encodingToken.componentTokenCount();
        }

        indent(out, 1, "}\n"); // end impl
        indent(out, 0, "} // end encoder mod \n");
    }

    private static void generateCompositeDecoder(
        final List<Token> tokens,
        final String decoderName,
        final Writer out) throws IOException
    {
        indent(out, 0, "pub mod decoder {\n");
        indent(out, 1, "use super::*;\n\n");

        indent(out, 1, "#[derive(Debug, Default)]\n");
        indent(out, 1, "pub struct %s<P> {\n", decoderName);
        indent(out, 2, "parent: Option<P>,\n");
        indent(out, 2, "offset: usize,\n");
        indent(out, 1, "}\n\n");

        appendImplReaderForComposite(out, 1, decoderName);

        // impl<'a, P> start
        indent(out, 1, "impl<P> %s<P> where P: Reader + Default {\n", decoderName);
        indent(out, 2, "pub fn wrap(mut self, parent: P, offset: usize) -> Self {\n");
        indent(out, 3, "self.parent = Some(parent);\n");
        indent(out, 3, "self.offset = offset;\n");
        indent(out, 3, "self\n");
        indent(out, 2, "}\n\n");

        // parent fn...
        indent(out, 2, "#[inline]\n");
        indent(out, 2, "pub fn parent(&mut self) -> SbeResult<P> {\n");
        indent(out, 3, "self.parent.take().ok_or(SbeErr::ParentNotSet)\n");
        indent(out, 2, "}\n\n");

        for (int i = 1, end = tokens.size() - 1; i < end; )
        {
            final Token encodingToken = tokens.get(i);
            final StringBuilder sb = new StringBuilder();

            switch (encodingToken.signal()) {
                case ENCODING -> generatePrimitiveDecoder(
                        sb, 2, encodingToken, encodingToken, encodingToken.name(), encodingToken.encoding());
                case BEGIN_ENUM -> generateEnumDecoder(sb, 2, encodingToken, encodingToken, encodingToken.name());
                case BEGIN_SET -> generateBitSetDecoder(sb, 2, encodingToken, encodingToken.name());
                case BEGIN_COMPOSITE ->
                        generateCompositeDecoder(sb, 2, encodingToken, encodingToken, encodingToken.name());
                default -> {
                }
            }

            out.append(sb);
            i += encodingToken.componentTokenCount();
        }

        indent(out, 1, "}\n"); // end impl
        indent(out, 0, "} // end decoder mod \n");
    }

    private static void appendConstAccessor(
        final Appendable writer,
        final String name,
        final String rustTypeName,
        final String rustExpression,
        final int level) throws IOException
    {
        indent(writer, level, "#[inline]\n");
        indent(writer, level, "pub fn %s(&self) -> %s {\n", formatFunctionName(name), rustTypeName);
        indent(writer, level + 1, rustExpression + "\n");
        indent(writer, level, "}\n\n");
    }
}
