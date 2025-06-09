package org.fog.test.perfeval;

public class Task {
    public String id;
    public int inputSize;
    public int outputSize;
    public int cycles;
    public int deadline;

    public Task(String id, int inputSize, int outputSize, int cycles, int deadline) {
        this.id = id;
        this.inputSize = inputSize;
        this.outputSize = outputSize;
        this.cycles = cycles;
        this.deadline = deadline;
    }

    @Override
    public String toString() {
        return "Task{" +
                "id='" + id + '\'' +
                ", inputSize=" + inputSize +
                ", outputSize=" + outputSize +
                ", cycles=" + cycles +
                ", deadline=" + deadline +
                '}';
    }
}
