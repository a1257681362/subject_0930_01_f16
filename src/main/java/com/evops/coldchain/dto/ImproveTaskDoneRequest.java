package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 改进任务完成确认（V15）：PENDING -> DONE 只进不退 */
public class ImproveTaskDoneRequest {
    @NotBlank(message = "完成说明不能为空")
    private String doneNote;

    public String getDoneNote() { return doneNote; }
    public void setDoneNote(String doneNote) { this.doneNote = doneNote; }
}
