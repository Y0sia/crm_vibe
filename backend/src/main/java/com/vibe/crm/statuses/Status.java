package com.vibe.crm.statuses;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Этап воронки. Порядок задаёт position; won отмечает статус «Сделка» для статистики. */
@Entity
@Table(name = "statuses")
public class Status {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64, unique = true)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private StatusColor color;

	@Column(nullable = false)
	private int position;

	@Column(nullable = false)
	private boolean won;

	protected Status() {
	}

	public Status(String name, StatusColor color, int position) {
		this(name, color, position, false);
	}

	public Status(String name, StatusColor color, int position, boolean won) {
		this.name = name.trim();
		this.color = color;
		this.position = position;
		this.won = won;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name.trim();
	}

	public StatusColor getColor() {
		return color;
	}

	public void setColor(StatusColor color) {
		this.color = color;
	}

	public int getPosition() {
		return position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public boolean isWon() {
		return won;
	}

}
