package dev.probeloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.probeloom.model.PlatformModels;
import dev.probeloom.repository.PlatformRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.InputStream;

@Service
public class CaseWorkbookService {
    private final PlatformRepository repository;
    private final CaseWorkbookCodec codec;

    public CaseWorkbookService(PlatformRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.codec = new CaseWorkbookCodec(objectMapper);
    }

    public byte[] template() {
        return codec.template(repository.listDataSources(), repository.listEnvironments());
    }

    public byte[] exportCase(Long caseId) {
        return exportCase(repository.loadCase(caseId));
    }

    public byte[] exportCase(PlatformModels.TestCase value) {
        return codec.write(value);
    }

    public PlatformModels.TestCase parseCase(InputStream input) {
        return codec.parse(input);
    }

    @Transactional
    public PlatformModels.TestCase importCase(InputStream input, Long groupId) {
        PlatformModels.TestCase value = parseCase(input);
        value.groupId = groupId;
        PlatformModels.TestCase existing = repository.findCaseByGroupAndName(groupId, value.name);
        PlatformModels.TestCase recycled = existing == null
                ? repository.findRecycledCaseByGroupAndName(groupId, value.name) : null;
        if (recycled != null) {
            repository.restoreCase(recycled.id);
            value.id = recycled.id;
        } else if (existing != null) {
            value.id = existing.id;
        }
        value.replaceActions = true;
        PlatformModels.TestCase saved = repository.saveCase(value);
        return repository.loadCase(saved.id);
    }

}
