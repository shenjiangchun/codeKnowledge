package com.huawei.hisi.glossary.repository;

import com.huawei.hisi.glossary.model.GlossaryTerm;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class GlossaryTermRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<GlossaryTerm> ROW_MAPPER = (rs, rowNum) ->
        GlossaryTerm.builder()
            .id(rs.getLong("id"))
            .projectPath(rs.getString("project_path"))
            .term(rs.getString("term"))
            .synonym(rs.getString("synonym"))
            .context(rs.getString("context"))
            .createdAt(rs.getObject("created_at") != null ? rs.getLong("created_at") : null)
            .updatedAt(rs.getObject("updated_at") != null ? rs.getLong("updated_at") : null)
            .build();

    public GlossaryTerm insert(GlossaryTerm term) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO glossary_term (project_path, term, synonym, context) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, term.getProjectPath());
            ps.setString(2, term.getTerm());
            ps.setString(3, term.getSynonym());
            ps.setString(4, term.getContext());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            term.setId(key.longValue());
        }
        return term;
    }

    public int update(GlossaryTerm term) {
        return jdbcTemplate.update(
            "UPDATE glossary_term SET term = ?, synonym = ?, context = ?, " +
            "updated_at = strftime('%s','now') WHERE id = ?",
            term.getTerm(), term.getSynonym(), term.getContext(), term.getId()
        );
    }

    public Optional<GlossaryTerm> findById(Long id) {
        List<GlossaryTerm> results = jdbcTemplate.query(
            "SELECT * FROM glossary_term WHERE id = ?", ROW_MAPPER, id
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<GlossaryTerm> findByProjectPath(String projectPath) {
        return jdbcTemplate.query(
            "SELECT * FROM glossary_term WHERE project_path = ? ORDER BY updated_at DESC",
            ROW_MAPPER, projectPath
        );
    }

    public int deleteById(Long id) {
        return jdbcTemplate.update("DELETE FROM glossary_term WHERE id = ?", id);
    }
}
