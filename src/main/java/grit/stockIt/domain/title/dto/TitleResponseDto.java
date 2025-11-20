package grit.stockIt.domain.title.dto;

import grit.stockIt.domain.title.entity.Title;
import lombok.Getter;

@Getter
public class TitleResponseDto {
    private final String name;
    private final String description;

    public TitleResponseDto(Title title) {
        this.name = title.getName();
        this.description = title.getDescription();
    }
}