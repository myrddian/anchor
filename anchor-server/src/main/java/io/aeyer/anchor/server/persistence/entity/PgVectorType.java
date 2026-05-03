package io.aeyer.anchor.server.persistence.entity;

import com.pgvector.PGvector;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Hibernate UserType bridging Postgres {@code vector(N)} to {@code float[]}.
 * pgvector-java's {@link PGvector} handles wire-format on both sides; this is
 * the Hibernate glue.
 */
public class PgVectorType implements UserType<float[]> {

    @Override
    public int getSqlType() { return Types.OTHER; }

    @Override
    public Class<float[]> returnedClass() { return float[].class; }

    @Override
    public boolean equals(float[] x, float[] y) { return Arrays.equals(x, y); }

    @Override
    public int hashCode(float[] x) { return Arrays.hashCode(x); }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position,
                               SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        // Without per-connection PGvector.addVectorType registration, the driver hands the
        // vector column back as a String like "[1.0,2.0]". Parse that ourselves so the
        // Hibernate integration stays self-contained.
        String text = rs.getString(position);
        if (text == null) return null;
        return new PGvector(text).toArray();
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index,
                            SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
            return;
        }
        org.postgresql.util.PGobject obj = new org.postgresql.util.PGobject();
        obj.setType("vector");
        obj.setValue(toPgVectorText(value));
        st.setObject(index, obj);
    }

    private static String toPgVectorText(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 6 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    @Override
    public float[] deepCopy(float[] value) { return value == null ? null : value.clone(); }

    @Override
    public boolean isMutable() { return true; }

    @Override
    public Serializable disassemble(float[] value) { return deepCopy(value); }

    @Override
    public float[] assemble(Serializable cached, Object owner) { return deepCopy((float[]) cached); }
}
