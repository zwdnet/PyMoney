# -*- coding:utf-8 -*-

# 真正的主程序
import os
from functions import *


#获取用户输入
def GetInput():
    errMsg = "输入错误请重新输入，按任意键继续"
    while True:
        os.system("clear")
        information = "您好，欢迎使用记账程序，请按提示输入。\n"
        information += "1.插入新的收入支出项目。\n"
        information += "2.按时间查找收入支出项目。\n"
        information += "3.按收入支出项目类型查找收入支出项目。\n"
        information += "4.删除指定的收入支出项目。\n"
        information += "5.指定日期范围内的现金流量表。\n"
        information += "6.指定日期范围内的个人年度通胀率。\n"
        information += "7.退出程序。\n"
        print(information)
        
        choise = input("请输入您选择的功能:")
        if choise.isdecimal() == False:
            input(errMsg)
            continue
        choiseNum = int(choise)
        if choiseNum < 1 or choiseNum > 7:
            input(errMsg)
            continue
        else:
            return choiseNum
    
    
if __name__ == "__main__":
    while True:
        choose = GetInput()
        if choose == 1: #插入新项目
            InsertData()
        elif choose == 2: #按时间查找
            SearchByTime()
        elif choose == 3: #按项目类型查找
            SearchByType()
        elif choose == 4: #删除项目
            DelData()
        elif choose == 5: #输出现金流量表
            Status()
        elif choose == 6: #输出CPI
            CPI()
        elif choose == 7: #退出
            print("谢谢使用，再见！")
            break
            
